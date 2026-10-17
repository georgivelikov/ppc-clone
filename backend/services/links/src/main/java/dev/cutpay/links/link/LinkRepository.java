package dev.cutpay.links.link;

import static dev.cutpay.links.config.Sql.instant;
import static dev.cutpay.links.config.Sql.ts;

import dev.cutpay.links.link.Link.LineItem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
class LinkRepository {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final String COLUMNS = """
            id, merchant_id, merchant_name, livemode, slug, type, amount, currency, min_amount, max_amount, title,
            description, image_url, array_to_string(collect, ',') AS collect_csv, quantity_adjustable, max_uses,
            uses_count, reserved, amount_collected, expires_at, active, after_completion, redirect_url,
            success_message, metadata::text AS metadata_json, qr_style::text AS qr_style_json, created_at, updated_at
            """;

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    LinkRepository(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void insert(Link l) {
        jdbc.sql("""
                        INSERT INTO payment_links (id, merchant_id, merchant_name, livemode, slug, type, amount, currency,
                            min_amount, max_amount, title, description, image_url, collect, quantity_adjustable, max_uses,
                            expires_at, active, after_completion, redirect_url, success_message, metadata, qr_style,
                            created_at, updated_at)
                        VALUES (:id, :merchantId, :merchantName, :livemode, :slug, :type, :amount, :currency, :min, :max,
                            :title, :description, :imageUrl, string_to_array(:collect, ',')::text[], :quantityAdjustable,
                            :maxUses, :expiresAt, TRUE, :afterCompletion, :redirectUrl, :successMessage,
                            CAST(:metadata AS jsonb), CAST(:qrStyle AS jsonb), :now, :now)
                        """)
                .param("id", l.id()).param("merchantId", l.merchantId()).param("merchantName", l.merchantName())
                .param("livemode", l.livemode()).param("slug", l.slug()).param("type", l.type())
                .param("amount", l.amount()).param("currency", l.currency()).param("min", l.minAmount())
                .param("max", l.maxAmount()).param("title", l.title()).param("description", l.description())
                .param("imageUrl", l.imageUrl()).param("collect", String.join(",", l.collect()))
                .param("quantityAdjustable", l.quantityAdjustable()).param("maxUses", l.maxUses())
                .param("expiresAt", ts(l.expiresAt())).param("afterCompletion", l.afterCompletion())
                .param("redirectUrl", l.redirectUrl()).param("successMessage", l.successMessage())
                .param("metadata", mapper.writeValueAsString(l.metadata()))
                .param("qrStyle", mapper.writeValueAsString(l.qrStyle())).param("now", ts(l.createdAt()))
                .update();
        int position = 0;
        for (LineItem item : l.lineItems()) {
            jdbc.sql("""
                            INSERT INTO link_line_items (link_id, position, product_name, unit_amount, quantity)
                            VALUES (:id, :position, :name, :unit, :quantity)
                            """)
                    .param("id", l.id()).param("position", position++).param("name", item.productName())
                    .param("unit", item.unitAmount()).param("quantity", item.quantity()).update();
        }
    }

    Optional<Link> findById(String id) {
        return one("SELECT " + COLUMNS + " FROM payment_links WHERE id = :id", id);
    }

    Optional<Link> findBySlug(String slug) {
        return one("SELECT " + COLUMNS + " FROM payment_links WHERE slug = :id", slug);
    }

    private Optional<Link> one(String sql, String key) {
        return jdbc.sql(sql).param("id", key).query(this::map).optional().map(this::withItems);
    }

    List<Link> list(String merchantId, boolean livemode, String status, String startingAfter, int limit) {
        var sql = new StringBuilder("SELECT " + COLUMNS
                + " FROM payment_links WHERE merchant_id = :merchant AND livemode = :livemode");
        if ("active".equals(status)) {
            sql.append(" AND active");
        } else if ("inactive".equals(status)) {
            sql.append(" AND NOT active");
        }
        boolean paged = startingAfter != null && !startingAfter.isBlank();
        if (paged) {
            sql.append(" AND id < :after");
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");
        var statement = jdbc.sql(sql.toString()).param("merchant", merchantId).param("livemode", livemode)
                .param("limit", limit);
        if (paged) {
            statement = statement.param("after", startingAfter);
        }
        return statement.query(this::map).list().stream().map(this::withItems).toList();
    }

    void update(Link l) {
        jdbc.sql("""
                        UPDATE payment_links SET title = :title, description = :description, image_url = :imageUrl,
                            collect = string_to_array(:collect, ',')::text[], max_uses = :maxUses,
                            expires_at = :expiresAt, active = :active, after_completion = :afterCompletion,
                            redirect_url = :redirectUrl, success_message = :successMessage,
                            metadata = CAST(:metadata AS jsonb), qr_style = CAST(:qrStyle AS jsonb), updated_at = :now
                        WHERE id = :id
                        """)
                .param("id", l.id()).param("title", l.title()).param("description", l.description())
                .param("imageUrl", l.imageUrl()).param("collect", String.join(",", l.collect()))
                .param("maxUses", l.maxUses()).param("expiresAt", ts(l.expiresAt())).param("active", l.active())
                .param("afterCompletion", l.afterCompletion()).param("redirectUrl", l.redirectUrl())
                .param("successMessage", l.successMessage()).param("metadata", mapper.writeValueAsString(l.metadata()))
                .param("qrStyle", mapper.writeValueAsString(l.qrStyle())).param("now", ts(l.updatedAt())).update();
    }

    boolean slugExists(String slug) {
        return jdbc.sql("SELECT COUNT(*) FROM payment_links WHERE slug = :slug").param("slug", slug)
                .query(Long.class).single() > 0;
    }

    /** Ids of links whose expiry has passed but which are still marked active. */
    List<String> expiredButActive(Instant now) {
        return jdbc.sql("SELECT id FROM payment_links WHERE active AND expires_at IS NOT NULL AND expires_at <= :now")
                .param("now", ts(now)).query(String.class).list();
    }

    private Link withItems(Link l) {
        List<LineItem> items = jdbc.sql("""
                        SELECT product_name, unit_amount, quantity FROM link_line_items
                        WHERE link_id = :id ORDER BY position
                        """)
                .param("id", l.id())
                .query((rs, n) -> new LineItem(rs.getString("product_name"), rs.getLong("unit_amount"),
                        rs.getInt("quantity")))
                .list();
        return new Link(l.id(), l.merchantId(), l.merchantName(), l.livemode(), l.slug(), l.type(), l.amount(),
                l.currency(), l.minAmount(), l.maxAmount(), l.title(), l.description(), l.imageUrl(), l.collect(),
                l.quantityAdjustable(), l.maxUses(), l.usesCount(), l.reserved(), l.amountCollected(), l.expiresAt(),
                l.active(), l.afterCompletion(), l.redirectUrl(), l.successMessage(), l.metadata(), l.qrStyle(),
                l.createdAt(), l.updatedAt(), items);
    }

    private Link map(ResultSet rs, int row) throws SQLException {
        String csv = rs.getString("collect_csv");
        List<String> collect = csv == null || csv.isBlank() ? List.of() : new ArrayList<>(Arrays.asList(csv.split(",")));
        return new Link(rs.getString("id"), rs.getString("merchant_id"), rs.getString("merchant_name"),
                rs.getBoolean("livemode"), rs.getString("slug"), rs.getString("type"), nullableLong(rs, "amount"),
                rs.getString("currency"), nullableLong(rs, "min_amount"), nullableLong(rs, "max_amount"),
                rs.getString("title"), rs.getString("description"), rs.getString("image_url"), collect,
                rs.getBoolean("quantity_adjustable"), (Integer) rs.getObject("max_uses"), rs.getInt("uses_count"),
                rs.getInt("reserved"), rs.getLong("amount_collected"), instant(rs, "expires_at"),
                rs.getBoolean("active"), rs.getString("after_completion"), rs.getString("redirect_url"),
                rs.getString("success_message"), mapper.readValue(rs.getString("metadata_json"), STRING_MAP),
                mapper.readValue(rs.getString("qr_style_json"), STRING_MAP), instant(rs, "created_at"),
                instant(rs, "updated_at"), List.of());
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
