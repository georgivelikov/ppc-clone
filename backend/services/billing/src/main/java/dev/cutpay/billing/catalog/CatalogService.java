package dev.cutpay.billing.catalog;

import static dev.cutpay.billing.config.Sql.instant;
import static dev.cutpay.billing.config.Sql.ts;

import dev.cutpay.billing.subscription.Interval;
import dev.cutpay.money.SupportedCurrencies;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a merchant sells: products, and the prices they can be bought at. A price never changes after it is created,
 * so every subscription keeps the terms it signed up for. To change what something costs, archive the price and add
 * a new one.
 */
@Service
public class CatalogService {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    public record Product(String id, String merchantId, boolean livemode, String name, String description,
                          String imageUrl, boolean active, Map<String, String> metadata, Instant createdAt) {
    }

    public record Price(String id, String merchantId, boolean livemode, String productId, long unitAmount,
                        String currency, String type, Interval interval, int intervalCount, int trialDays,
                        String nickname, boolean active, Instant createdAt) {
        public boolean recurring() {
            return "recurring".equals(type);
        }
    }

    public record NewProduct(String name, String description, String imageUrl, Map<String, String> metadata) {
    }

    public record ProductChanges(String name, String description, String imageUrl, Boolean active,
                                 Map<String, String> metadata) {
    }

    public record NewPrice(String productId, long unitAmount, String currency, String interval, Integer intervalCount,
                           Integer trialDays, String nickname) {
    }

    public record Page<T>(List<T> data, boolean hasMore) {
    }

    private static final String PRODUCT_COLUMNS = """
            id, merchant_id, livemode, name, description, image_url, active, metadata::text AS metadata_json, created_at
            """;
    private static final String PRICE_COLUMNS = """
            id, merchant_id, livemode, product_id, unit_amount, currency, type, billing_interval, interval_count,
            trial_days, nickname, active, created_at
            """;

    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final Clock clock;

    public CatalogService(JdbcClient jdbc, JsonMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    // --- products ----------------------------------------------------------------------------------

    @Transactional
    public Product createProduct(String merchantId, boolean livemode, NewProduct request) {
        String id = Ids.generate("prod");
        Instant now = clock.instant();
        jdbc.sql("""
                        INSERT INTO products (id, merchant_id, livemode, name, description, image_url, active, metadata,
                                              created_at, updated_at)
                        VALUES (:id, :merchant, :livemode, :name, :description, :image, TRUE, CAST(:metadata AS jsonb),
                                :now, :now)
                        """)
                .param("id", id).param("merchant", merchantId).param("livemode", livemode)
                .param("name", request.name().trim()).param("description", blankToNull(request.description()))
                .param("image", blankToNull(request.imageUrl()))
                .param("metadata", mapper.writeValueAsString(request.metadata() == null ? Map.of() : request.metadata()))
                .param("now", ts(now)).update();
        return product(merchantId, null, id);
    }

    public Product product(String merchantId, Boolean claimedMode, String id) {
        return findProduct(id).filter(p -> p.merchantId().equals(merchantId))
                .filter(p -> claimedMode == null || claimedMode == p.livemode())
                .orElseThrow(() -> ApiException.notFound("product_not_found", "No such product"));
    }

    @Transactional
    public Product updateProduct(String merchantId, Boolean claimedMode, String id, ProductChanges changes) {
        Product current = product(merchantId, claimedMode, id);
        jdbc.sql("""
                        UPDATE products SET name = :name, description = :description, image_url = :image,
                               active = :active, metadata = CAST(:metadata AS jsonb), updated_at = :now
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("name", changes.name() == null ? current.name() : changes.name().trim())
                .param("description", changes.description() == null ? current.description() : blankToNull(changes.description()))
                .param("image", changes.imageUrl() == null ? current.imageUrl() : blankToNull(changes.imageUrl()))
                .param("active", changes.active() == null ? current.active() : changes.active())
                .param("metadata", mapper.writeValueAsString(changes.metadata() == null ? current.metadata() : changes.metadata()))
                .param("now", ts(clock.instant())).update();
        return product(merchantId, claimedMode, id);
    }

    public Page<Product> products(String merchantId, boolean livemode, Boolean active, String startingAfter, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        List<Product> found = jdbc.sql("SELECT " + PRODUCT_COLUMNS + """
                         FROM products
                        WHERE merchant_id = :merchant AND livemode = :livemode
                          AND (CAST(:active AS boolean) IS NULL OR active = :active)
                          AND (CAST(:after AS text) IS NULL OR id < :after)
                        ORDER BY id DESC LIMIT :limit
                        """)
                .param("merchant", merchantId).param("livemode", livemode).param("active", active, java.sql.Types.BOOLEAN)
                .param("after", blankToNull(startingAfter), java.sql.Types.VARCHAR).param("limit", size + 1)
                .query(this::product).list();
        return new Page<>(found.stream().limit(size).toList(), found.size() > size);
    }

    // --- prices ------------------------------------------------------------------------------------

    @Transactional
    public Price createPrice(String merchantId, boolean livemode, NewPrice request) {
        Product product = product(merchantId, livemode, request.productId());
        if (!product.active()) {
            throw ApiException.invalidRequest("product_archived", "This product is archived", "product");
        }
        String currency = request.currency() == null ? "" : request.currency().toUpperCase(Locale.ROOT);
        if (!SupportedCurrencies.isSupported(currency)) {
            throw ApiException.invalidRequest("unsupported_currency", "Currency is not supported", "currency");
        }
        boolean recurring = request.interval() != null && !request.interval().isBlank();
        Interval interval = null;
        if (recurring) {
            try {
                interval = Interval.fromWire(request.interval());
            } catch (IllegalArgumentException e) {
                throw ApiException.invalidRequest("invalid_interval", "Interval must be day, week, month or year", "interval");
            }
        } else if (request.intervalCount() != null || (request.trialDays() != null && request.trialDays() > 0)) {
            throw ApiException.invalidRequest("invalid_price", "Interval count and trial days only apply to recurring prices",
                    "interval");
        }
        int count = request.intervalCount() == null ? 1 : request.intervalCount();
        int trial = request.trialDays() == null ? 0 : request.trialDays();
        if (count < 1 || count > 365) {
            throw ApiException.invalidRequest("invalid_interval_count", "Interval count must be between 1 and 365", "interval_count");
        }
        if (trial < 0 || trial > 730) {
            throw ApiException.invalidRequest("invalid_trial", "Trial days must be between 0 and 730", "trial_days");
        }
        if (request.unitAmount() < 1 || request.unitAmount() > 99_999_999) {
            throw ApiException.invalidRequest("invalid_amount", "Amount must be between 1 and 99999999", "unit_amount");
        }
        String id = Ids.generate("price");
        jdbc.sql("""
                        INSERT INTO prices (id, merchant_id, livemode, product_id, unit_amount, currency, type,
                                            billing_interval, interval_count, trial_days, nickname, active, created_at)
                        VALUES (:id, :merchant, :livemode, :product, :amount, :currency, :type, :interval, :count, :trial,
                                :nickname, TRUE, :now)
                        """)
                .param("id", id).param("merchant", merchantId).param("livemode", livemode)
                .param("product", product.id()).param("amount", request.unitAmount()).param("currency", currency)
                .param("type", recurring ? "recurring" : "one_time")
                .param("interval", interval == null ? null : interval.wire(), java.sql.Types.VARCHAR)
                .param("count", count).param("trial", trial).param("nickname", blankToNull(request.nickname()))
                .param("now", ts(clock.instant())).update();
        return price(merchantId, null, id);
    }

    public Price price(String merchantId, Boolean claimedMode, String id) {
        return findPrice(id).filter(p -> p.merchantId().equals(merchantId))
                .filter(p -> claimedMode == null || claimedMode == p.livemode())
                .orElseThrow(() -> ApiException.notFound("price_not_found", "No such price"));
    }

    /** Only the label and whether the price can be bought change. The amount and interval never do. */
    @Transactional
    public Price updatePrice(String merchantId, Boolean claimedMode, String id, Boolean active, String nickname) {
        Price current = price(merchantId, claimedMode, id);
        jdbc.sql("UPDATE prices SET active = :active, nickname = :nickname WHERE id = :id")
                .param("id", id).param("active", active == null ? current.active() : active)
                .param("nickname", nickname == null ? current.nickname() : blankToNull(nickname)).update();
        return price(merchantId, claimedMode, id);
    }

    public Page<Price> prices(String merchantId, boolean livemode, String productId, Boolean active, String startingAfter,
                              int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        List<Price> found = jdbc.sql("SELECT " + PRICE_COLUMNS + """
                         FROM prices
                        WHERE merchant_id = :merchant AND livemode = :livemode
                          AND (CAST(:product AS text) IS NULL OR product_id = :product)
                          AND (CAST(:active AS boolean) IS NULL OR active = :active)
                          AND (CAST(:after AS text) IS NULL OR id < :after)
                        ORDER BY id DESC LIMIT :limit
                        """)
                .param("merchant", merchantId).param("livemode", livemode)
                .param("product", blankToNull(productId), java.sql.Types.VARCHAR)
                .param("active", active, java.sql.Types.BOOLEAN)
                .param("after", blankToNull(startingAfter), java.sql.Types.VARCHAR).param("limit", size + 1)
                .query(this::price).list();
        return new Page<>(found.stream().limit(size).toList(), found.size() > size);
    }

    // --- mapping -----------------------------------------------------------------------------------

    private Optional<Product> findProduct(String id) {
        return jdbc.sql("SELECT " + PRODUCT_COLUMNS + " FROM products WHERE id = :id").param("id", id)
                .query(this::product).optional();
    }

    private Optional<Price> findPrice(String id) {
        return jdbc.sql("SELECT " + PRICE_COLUMNS + " FROM prices WHERE id = :id").param("id", id)
                .query(this::price).optional();
    }

    private Product product(ResultSet rs, int row) throws SQLException {
        return new Product(rs.getString("id"), rs.getString("merchant_id"), rs.getBoolean("livemode"),
                rs.getString("name"), rs.getString("description"), rs.getString("image_url"), rs.getBoolean("active"),
                mapper.readValue(rs.getString("metadata_json"), STRING_MAP), instant(rs, "created_at"));
    }

    private Price price(ResultSet rs, int row) throws SQLException {
        String interval = rs.getString("billing_interval");
        return new Price(rs.getString("id"), rs.getString("merchant_id"), rs.getBoolean("livemode"),
                rs.getString("product_id"), rs.getLong("unit_amount"), rs.getString("currency").trim(),
                rs.getString("type"), interval == null ? null : Interval.fromWire(interval), rs.getInt("interval_count"),
                rs.getInt("trial_days"), rs.getString("nickname"), rs.getBoolean("active"), instant(rs, "created_at"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
