package dev.cutpay.links.link;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A payment link as stored. {@code lineItems} is empty unless the link is of type {@code products}. */
public record Link(
        String id, String merchantId, String merchantName, boolean livemode, String slug, String type, Long amount,
        String currency, Long minAmount, Long maxAmount, String title, String description, String imageUrl,
        List<String> collect, boolean quantityAdjustable, Integer maxUses, int usesCount, int reserved,
        long amountCollected, Instant expiresAt, boolean active, String afterCompletion, String redirectUrl,
        String successMessage, Map<String, String> metadata, Map<String, String> qrStyle, Instant createdAt,
        Instant updatedAt, List<LineItem> lineItems) {

    public static final String FIXED = "fixed";
    public static final String CUSTOMER_CHOOSES = "customer_chooses";
    public static final String PRODUCTS = "products";

    public record LineItem(String productName, long unitAmount, int quantity) {
    }

    /** Why a customer cannot pay right now, or null when they can. */
    public String unavailableReason(Instant now) {
        if (!active) {
            return "inactive";
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            return "expired";
        }
        if (maxUses != null && usesCount + reserved >= maxUses) {
            return "sold_out";
        }
        return null;
    }
}
