package dev.cutpay.links.link;

import static dev.cutpay.links.config.Sql.ts;

import dev.cutpay.events.InboxGuard;
import dev.cutpay.events.OutboxWriter;
import dev.cutpay.links.config.LinkProperties;
import dev.cutpay.links.config.Topics;
import dev.cutpay.links.link.Link.LineItem;
import dev.cutpay.links.link.LinkDtos.CreateLinkRequest;
import dev.cutpay.links.link.LinkDtos.LinkList;
import dev.cutpay.links.link.LinkDtos.LinkPayment;
import dev.cutpay.links.link.LinkDtos.LinkPaymentList;
import dev.cutpay.links.link.LinkDtos.LinkView;
import dev.cutpay.links.link.LinkDtos.LineItemView;
import dev.cutpay.links.link.LinkDtos.PublicLinkView;
import dev.cutpay.links.link.LinkDtos.SessionCreated;
import dev.cutpay.links.link.LinkDtos.SessionRequest;
import dev.cutpay.links.link.LinkDtos.UpdateLinkRequest;
import dev.cutpay.links.payments.PaymentsClient;
import dev.cutpay.money.SupportedCurrencies;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment links and the one thing that makes them interesting: a link with {@code max_uses} must never take more
 * payments than it allows, however many customers open it at once (docs/adr/0014-links-qr-and-virtual-pos.md).
 *
 * <p>The mechanism is a reservation. Opening the checkout for a link first takes one use with a single atomic
 * {@code UPDATE ... WHERE uses_count + reserved < max_uses}; the database decides who wins, so there is no
 * read-then-write gap. The use is held until the payment succeeds (it becomes a real use), fails or is canceled
 * (it is given back), or the reservation times out (a job gives it back).
 */
@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);
    private static final Set<String> TYPES = Set.of(Link.FIXED, Link.CUSTOMER_CHOOSES, Link.PRODUCTS);
    private static final Set<String> COLLECT = Set.of("email", "phone", "name", "address");
    private static final Set<String> AFTER = Set.of("message", "redirect");
    private static final int MAX_PAGE = 100;

    private final LinkRepository links;
    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final InboxGuard inbox;
    private final TransactionTemplate tx;
    private final PaymentsClient payments;
    private final LinkProperties properties;
    private final Clock clock;

    LinkService(LinkRepository links, JdbcClient jdbc, OutboxWriter outbox, InboxGuard inbox, TransactionTemplate tx,
                PaymentsClient payments, LinkProperties properties, Clock clock) {
        this.links = links;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.inbox = inbox;
        this.tx = tx;
        this.payments = payments;
        this.properties = properties;
        this.clock = clock;
    }

    // --- merchant side ---------------------------------------------------------------------------

    @Transactional
    public LinkView create(String merchantId, boolean livemode, CreateLinkRequest r) {
        Link link = build(merchantId, livemode, r);
        links.insert(link);
        publishCreated(link);
        return view(link);
    }

    @Transactional(readOnly = true)
    public LinkView get(String merchantId, Boolean claimedMode, String id) {
        return view(owned(merchantId, claimedMode, id));
    }

    @Transactional(readOnly = true)
    public LinkList list(String merchantId, boolean livemode, String status, String startingAfter, int limit) {
        int page = Math.max(1, Math.min(limit, MAX_PAGE));
        List<Link> rows = links.list(merchantId, livemode, status, startingAfter, page + 1);
        return new LinkList("list", rows.stream().limit(page).map(this::view).toList(), rows.size() > page);
    }

    @Transactional
    public LinkView update(String merchantId, Boolean claimedMode, String id, UpdateLinkRequest r) {
        Link old = owned(merchantId, claimedMode, id);
        List<String> collect = r.collect() == null ? old.collect() : validCollect(r.collect());
        String after = r.afterCompletion() == null ? old.afterCompletion() : r.afterCompletion();
        String redirect = r.redirectUrl() == null ? old.redirectUrl() : r.redirectUrl();
        checkCompletion(after, redirect);
        Integer maxUses = r.maxUses() == null ? old.maxUses() : r.maxUses();
        if (maxUses != null && maxUses < old.usesCount()) {
            throw ApiException.invalidRequest("max_uses_too_low",
                    "This link has already been used %d times".formatted(old.usesCount()), "max_uses");
        }
        boolean active = r.active() == null ? old.active() : r.active();
        Instant now = clock.instant();
        Link updated = new Link(old.id(), old.merchantId(), old.merchantName(), old.livemode(), old.slug(), old.type(),
                old.amount(), old.currency(), old.minAmount(), old.maxAmount(),
                r.title() == null ? old.title() : r.title(),
                r.description() == null ? old.description() : r.description(),
                r.imageUrl() == null ? old.imageUrl() : validImage(r.imageUrl()), collect, old.quantityAdjustable(),
                maxUses, old.usesCount(), old.reserved(), old.amountCollected(),
                r.expiresAt() == null ? old.expiresAt() : r.expiresAt(), active, after, redirect,
                r.successMessage() == null ? old.successMessage() : r.successMessage(),
                r.metadata() == null ? old.metadata() : validMetadata(r.metadata()),
                r.qrStyle() == null ? old.qrStyle() : validQrStyle(r.qrStyle()), old.createdAt(), now, old.lineItems());
        links.update(updated);
        if (old.active() && !active) {
            publishDeactivated(updated, "manual");
        }
        return view(updated);
    }

    @Transactional(readOnly = true)
    public LinkPaymentList payments(String merchantId, Boolean claimedMode, String id) {
        Link link = owned(merchantId, claimedMode, id);
        List<LinkPayment> rows = jdbc.sql("""
                        SELECT id, payment_id, status, amount, created_at FROM link_reservations
                        WHERE link_id = :id AND payment_id IS NOT NULL AND status = 'used'
                        ORDER BY created_at DESC LIMIT 100
                        """)
                .param("id", link.id())
                .query((rs, n) -> new LinkPayment(rs.getString("payment_id"), rs.getString("id"),
                        rs.getString("status"), rs.getLong("amount"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()))
                .list();
        return new LinkPaymentList("list", rows);
    }

    public Link owned(String merchantId, Boolean claimedMode, String id) {
        return links.findById(id)
                .filter(l -> l.merchantId().equals(merchantId))
                .filter(l -> claimedMode == null || l.livemode() == claimedMode)
                .orElseThrow(() -> ApiException.notFound("link_not_found", "No such payment link"));
    }

    // --- customers -------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PublicLinkView publicView(String slug) {
        Link l = links.findBySlug(slug).orElseThrow(LinkService::notFound);
        String reason = l.unavailableReason(clock.instant());
        return new PublicLinkView(l.slug(), l.livemode(), l.merchantName(), l.type(), l.amount(), l.currency(),
                l.minAmount(), l.maxAmount(), l.title(), l.description(), l.imageUrl(), l.collect(),
                l.quantityAdjustable(), l.lineItems().stream().map(LineItemView::of).toList(), reason == null, reason,
                l.afterCompletion(), l.successMessage());
    }

    /**
     * Takes one use of the link, then starts a payment for it. If the link has no use left the customer is told
     * so before any payment exists; if starting the payment fails the use is given back at once.
     */
    public SessionCreated createSession(String slug, SessionRequest r) {
        Link link = links.findBySlug(slug).orElseThrow(LinkService::notFound);
        long amount = LinkPricing.total(link, r.amount(), r.quantity());
        requireCollected(link, r);

        String reservationId = Ids.generate("lres");
        Instant now = clock.instant();
        boolean reserved = Boolean.TRUE.equals(tx.execute(status -> reserve(link.id(), reservationId, amount, now)));
        if (!reserved) {
            throw ApiException.conflict("link_unavailable", "This payment link is no longer available", null);
        }

        Map<String, String> metadata = new LinkedHashMap<>(link.metadata());
        metadata.put("link_id", link.id());
        metadata.put("link_slug", link.slug());
        metadata.put("link_reservation_id", reservationId);
        putIfPresent(metadata, "customer_name", r.name());
        putIfPresent(metadata, "customer_phone", r.phone());
        putIfPresent(metadata, "customer_address", r.address());

        PaymentsClient.Created created;
        try {
            created = payments.create(link.merchantId(), link.livemode(), link.merchantName(), amount, link.currency(),
                    link.title(), blankToNull(r.email()), metadata, returnUrl(link));
        } catch (RuntimeException e) {
            release(reservationId);
            throw e;
        }
        jdbc.sql("UPDATE link_reservations SET payment_id = :payment WHERE id = :id")
                .param("payment", created.paymentId()).param("id", reservationId).update();
        return new SessionCreated(created.paymentId(), created.clientSecret(), created.url());
    }

    /** Where the customer lands after paying: the merchant's own page, or a thank-you page of ours. */
    private String returnUrl(Link link) {
        if ("redirect".equals(link.afterCompletion()) && link.redirectUrl() != null) {
            return link.redirectUrl();
        }
        return properties.checkoutUrl() + "/l/" + link.slug() + "/thanks";
    }

    private boolean reserve(String linkId, String reservationId, long amount, Instant now) {
        int taken = jdbc.sql("""
                        UPDATE payment_links SET reserved = reserved + 1, updated_at = :now
                        WHERE id = :id AND active
                          AND (expires_at IS NULL OR expires_at > :now)
                          AND (max_uses IS NULL OR uses_count + reserved < max_uses)
                        """)
                .param("id", linkId).param("now", ts(now)).update();
        if (taken == 0) {
            return false;
        }
        jdbc.sql("""
                        INSERT INTO link_reservations (id, link_id, amount, status, expires_at, created_at, updated_at)
                        VALUES (:id, :link, :amount, 'held', :expires, :now, :now)
                        """)
                .param("id", reservationId).param("link", linkId).param("amount", amount)
                .param("expires", ts(now.plus(properties.reservationTtl()))).param("now", ts(now)).update();
        return true;
    }

    // --- payment results (Kafka) -----------------------------------------------------------------

    /** The payment succeeded: the held use becomes a real one, and a link that has used them all closes. */
    @Transactional
    public void onPaymentSucceeded(String eventId, String linkId, String reservationId, long amount) {
        if (!inbox.firstTime(eventId)) {
            return;
        }
        Instant now = clock.instant();
        String previous = reservationId == null ? null : jdbc.sql(
                        "SELECT status FROM link_reservations WHERE id = :id FOR UPDATE")
                .param("id", reservationId).query(String.class).optional().orElse(null);
        if ("used".equals(previous)) {
            return;
        }
        if (reservationId != null && previous != null) {
            jdbc.sql("UPDATE link_reservations SET status = 'used', updated_at = :now WHERE id = :id")
                    .param("id", reservationId).param("now", ts(now)).update();
        }
        // A reservation that timed out has already given its place back; the money was taken regardless, so the
        // use is counted without adjusting what is reserved.
        int heldToGive = "held".equals(previous) ? 1 : 0;
        int updated = jdbc.sql("""
                        UPDATE payment_links SET uses_count = uses_count + 1, reserved = reserved - :held,
                            amount_collected = amount_collected + :amount, updated_at = :now
                        WHERE id = :id
                        """)
                .param("id", linkId).param("held", heldToGive).param("amount", amount).param("now", ts(now)).update();
        if (updated == 0) {
            log.warn("Payment for unknown link {}", linkId);
            return;
        }
        Link link = links.findById(linkId).orElseThrow();
        if (link.active() && link.maxUses() != null && link.usesCount() >= link.maxUses()) {
            deactivate(link, "max_uses_reached");
        }
    }

    /** The payment failed or was canceled: the customer may try again, and the use is free for someone else. */
    @Transactional
    public void onPaymentEnded(String eventId, String reservationId) {
        if (!inbox.firstTime(eventId) || reservationId == null) {
            return;
        }
        releaseInTransaction(reservationId);
    }

    // --- housekeeping (jobs) ---------------------------------------------------------------------

    /** Gives back uses held by customers who never finished paying. Returns how many were given back. */
    @Transactional
    public int releaseExpiredReservations() {
        Instant now = clock.instant();
        return jdbc.sql("""
                        WITH released AS (
                            UPDATE link_reservations SET status = 'released', updated_at = :now
                            WHERE status = 'held' AND expires_at <= :now
                            RETURNING link_id
                        ), counts AS (
                            SELECT link_id, COUNT(*) AS n FROM released GROUP BY link_id
                        )
                        UPDATE payment_links l SET reserved = l.reserved - c.n, updated_at = :now
                        FROM counts c WHERE l.id = c.link_id
                        """)
                .param("now", ts(now)).update();
    }

    /** Closes links whose expiry date has passed, so webhooks and the dashboard see them as inactive. */
    @Transactional
    public int deactivateExpiredLinks() {
        int count = 0;
        for (String id : links.expiredButActive(clock.instant())) {
            Link link = links.findById(id).orElse(null);
            if (link != null && deactivate(link, "expired")) {
                count++;
            }
        }
        return count;
    }

    // --- internals -------------------------------------------------------------------------------

    private void release(String reservationId) {
        tx.execute(status -> {
            releaseInTransaction(reservationId);
            return null;
        });
    }

    /** One statement: the reservation is released and the link gets its use back only if it was still held. */
    private void releaseInTransaction(String reservationId) {
        jdbc.sql("""
                        WITH released AS (
                            UPDATE link_reservations SET status = 'released', updated_at = :now
                            WHERE id = :id AND status = 'held' RETURNING link_id
                        )
                        UPDATE payment_links l SET reserved = l.reserved - 1, updated_at = :now
                        FROM released r WHERE l.id = r.link_id
                        """)
                .param("id", reservationId).param("now", ts(clock.instant())).update();
    }

    private boolean deactivate(Link link, String reason) {
        int changed = jdbc.sql("UPDATE payment_links SET active = FALSE, updated_at = :now WHERE id = :id AND active")
                .param("id", link.id()).param("now", ts(clock.instant())).update();
        if (changed == 1) {
            publishDeactivated(link, reason);
        }
        return changed == 1;
    }

    private Link build(String merchantId, boolean livemode, CreateLinkRequest r) {
        String type = r.type();
        if (!TYPES.contains(type)) {
            throw ApiException.invalidRequest("invalid_type", "Type must be fixed, customer_chooses or products", "type");
        }
        String currency = r.currency().toUpperCase(Locale.ROOT);
        if (!SupportedCurrencies.isSupported(currency)) {
            throw ApiException.invalidRequest("unsupported_currency", "Currency is not supported", "currency");
        }
        boolean adjustable = Boolean.TRUE.equals(r.quantityAdjustable());
        Long amount = null;
        Long min = null;
        Long max = null;
        List<LineItem> items = List.of();
        switch (type) {
            case Link.FIXED -> {
                amount = positive(r.amount(), "amount");
            }
            case Link.CUSTOMER_CHOOSES -> {
                min = r.minAmount() == null ? 100L : positive(r.minAmount(), "min_amount");
                max = r.maxAmount() == null ? LinkPricing.MAX_AMOUNT : positive(r.maxAmount(), "max_amount");
                if (min > max || max > LinkPricing.MAX_AMOUNT) {
                    throw ApiException.invalidRequest("invalid_range", "The minimum must not exceed the maximum",
                            "min_amount");
                }
                if (adjustable) {
                    throw ApiException.invalidRequest("quantity_not_supported",
                            "Quantity cannot be adjusted when the customer chooses the amount", "quantity_adjustable");
                }
            }
            default -> {
                if (r.lineItems() == null || r.lineItems().isEmpty()) {
                    throw ApiException.invalidRequest("line_items_required", "Add at least one item", "line_items");
                }
                items = r.lineItems().stream()
                        .map(i -> new LineItem(i.productName().trim(), i.unitAmount(), i.quantity())).toList();
                long total = 0;
                for (LineItem item : items) {
                    total += item.unitAmount() * item.quantity();
                    if (total > LinkPricing.MAX_AMOUNT) {
                        throw ApiException.invalidRequest("amount_too_large", "The total is above the maximum payment",
                                "line_items");
                    }
                }
            }
        }
        String after = r.afterCompletion() == null ? "message" : r.afterCompletion();
        checkCompletion(after, r.redirectUrl());
        Instant now = clock.instant();
        if (r.expiresAt() != null && !r.expiresAt().isAfter(now)) {
            throw ApiException.invalidRequest("invalid_expiry", "The expiry must be in the future", "expires_at");
        }
        return new Link(Ids.generate("plink"), merchantId, r.merchantName(), livemode, uniqueSlug(), type, amount,
                currency, min, max, r.title().trim(), r.description(), validImage(r.imageUrl()),
                validCollect(r.collect() == null ? List.of() : r.collect()), adjustable, r.maxUses(), 0, 0,
                0, r.expiresAt(), true, after, r.redirectUrl(), r.successMessage(),
                validMetadata(r.metadata() == null ? Map.of() : r.metadata()),
                validQrStyle(r.qrStyle() == null ? Map.of() : r.qrStyle()), now, now, items);
    }

    private String uniqueSlug() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String slug = Slugs.generate();
            if (!links.slugExists(slug)) {
                return slug;
            }
        }
        throw new IllegalStateException("Could not find a free slug");
    }

    private static long positive(Long value, String field) {
        if (value == null || value < 1 || value > LinkPricing.MAX_AMOUNT) {
            throw ApiException.invalidRequest("invalid_amount", "Enter an amount greater than zero", field);
        }
        return value;
    }

    private static void checkCompletion(String after, String redirectUrl) {
        if (!AFTER.contains(after)) {
            throw ApiException.invalidRequest("invalid_after_completion", "Use message or redirect",
                    "after_completion");
        }
        if ("redirect".equals(after)) {
            if (redirectUrl == null || !isHttpUrl(redirectUrl)) {
                throw ApiException.invalidRequest("invalid_redirect_url", "Enter a full http(s) address",
                        "redirect_url");
            }
        }
    }

    private static String validImage(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        if (!isHttpUrl(url)) {
            throw ApiException.invalidRequest("invalid_image_url", "Enter a full http(s) address", "image_url");
        }
        return url;
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static List<String> validCollect(List<String> collect) {
        for (String item : collect) {
            if (!COLLECT.contains(item)) {
                throw ApiException.invalidRequest("invalid_collect", "Collect can contain email, phone, name, address",
                        "collect");
            }
        }
        return collect.stream().distinct().toList();
    }

    private static Map<String, String> validMetadata(Map<String, String> metadata) {
        if (metadata.size() > 20) {
            throw ApiException.invalidRequest("invalid_metadata", "At most 20 metadata keys", "metadata");
        }
        return Map.copyOf(metadata);
    }

    /** Colours only for now: {@code fg} and {@code bg} as {@code #rrggbb}. */
    private static Map<String, String> validQrStyle(Map<String, String> style) {
        Map<String, String> clean = new LinkedHashMap<>();
        for (String key : List.of("fg", "bg")) {
            String value = style.get(key);
            if (value != null) {
                if (!value.matches("#[0-9a-fA-F]{6}")) {
                    throw ApiException.invalidRequest("invalid_qr_style", "Use colours like #1a2b3c", "qr_style");
                }
                clean.put(key, value.toLowerCase(Locale.ROOT));
            }
        }
        return clean;
    }

    private static void requireCollected(Link link, SessionRequest r) {
        if (link.collect().contains("email") && blank(r.email())) {
            throw ApiException.invalidRequest("email_required", "Enter your email address", "email");
        }
        if (link.collect().contains("name") && blank(r.name())) {
            throw ApiException.invalidRequest("name_required", "Enter your name", "name");
        }
        if (link.collect().contains("phone") && blank(r.phone())) {
            throw ApiException.invalidRequest("phone_required", "Enter your phone number", "phone");
        }
        if (link.collect().contains("address") && blank(r.address())) {
            throw ApiException.invalidRequest("address_required", "Enter your address", "address");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value.trim();
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (!blank(value)) {
            target.put(key, value.trim());
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("link_not_found", "This payment link does not exist");
    }

    private LinkView view(Link link) {
        return LinkView.of(link, properties.checkoutUrl(), clock.instant());
    }

    private void publishCreated(Link l) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("link_id", l.id());
        payload.put("slug", l.slug());
        payload.put("url", properties.checkoutUrl() + "/l/" + l.slug());
        payload.put("type", l.type());
        payload.put("title", l.title());
        payload.put("currency", l.currency());
        if (l.amount() != null) {
            payload.put("amount", l.amount());
        }
        outbox.write(Topics.LINK, "PaymentLinkCreated", l.id(), l.merchantId(), l.livemode(), payload);
    }

    private void publishDeactivated(Link l, String reason) {
        outbox.write(Topics.LINK, "PaymentLinkDeactivated", l.id(), l.merchantId(), l.livemode(),
                Map.of("link_id", l.id(), "slug", l.slug(), "reason", reason));
    }
}
