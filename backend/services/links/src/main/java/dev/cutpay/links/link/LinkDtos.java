package dev.cutpay.links.link;

import dev.cutpay.links.link.Link.LineItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class LinkDtos {

    private LinkDtos() {
    }

    public record LineItemInput(@NotBlank @Size(max = 120) String productName,
                                @Min(1) @Max(99_999_999) long unitAmount, @Min(1) @Max(99) int quantity) {
    }

    public record CreateLinkRequest(
            @NotBlank String type,
            Long amount,
            @NotBlank String currency,
            Long minAmount,
            Long maxAmount,
            @NotBlank @Size(max = 120) String title,
            @Size(max = 1000) String description,
            @Size(max = 2000) String imageUrl,
            List<String> collect,
            Boolean quantityAdjustable,
            @Min(1) Integer maxUses,
            Instant expiresAt,
            String afterCompletion,
            @Size(max = 2000) String redirectUrl,
            @Size(max = 500) String successMessage,
            Map<String, String> metadata,
            @Size(max = 160) String merchantName,
            @Valid @Size(max = 20) List<LineItemInput> lineItems,
            Map<String, String> qrStyle) {
    }

    /**
     * Only what can change without surprising people who already hold the link. Price, currency and type are fixed
     * for the life of a link: create a new one instead. Absent fields stay as they are.
     */
    public record UpdateLinkRequest(
            @Size(max = 120) String title,
            @Size(max = 1000) String description,
            @Size(max = 2000) String imageUrl,
            List<String> collect,
            @Min(1) Integer maxUses,
            Instant expiresAt,
            Boolean active,
            String afterCompletion,
            @Size(max = 2000) String redirectUrl,
            @Size(max = 500) String successMessage,
            Map<String, String> metadata,
            Map<String, String> qrStyle) {
    }

    public record LineItemView(String productName, long unitAmount, int quantity) {
        static LineItemView of(LineItem i) {
            return new LineItemView(i.productName(), i.unitAmount(), i.quantity());
        }
    }

    public record LinkView(String id, String object, boolean livemode, String url, String slug, String status,
                           String type, Long amount, String currency, Long minAmount, Long maxAmount, String title,
                           String description, String imageUrl, List<String> collect, boolean quantityAdjustable,
                           Integer maxUses, int usesCount, long amountCollected, Instant expiresAt, boolean active,
                           String afterCompletion, String redirectUrl, String successMessage,
                           Map<String, String> metadata, Map<String, String> qrStyle, List<LineItemView> lineItems,
                           Instant createdAt) {

        static LinkView of(Link l, String checkoutUrl, Instant now) {
            String reason = l.unavailableReason(now);
            // "sold_out" counts customers who are still paying, but the merchant sees a link as completed
            // only once the uses have really been paid.
            String status = !l.active() ? "inactive" : reason == null ? "active" : reason;
            if ("sold_out".equals(status) && l.maxUses() != null && l.usesCount() < l.maxUses()) {
                status = "active";
            }
            return new LinkView(l.id(), "payment_link", l.livemode(), checkoutUrl + "/l/" + l.slug(), l.slug(), status,
                    l.type(), l.amount(), l.currency(), l.minAmount(), l.maxAmount(), l.title(), l.description(),
                    l.imageUrl(), l.collect(), l.quantityAdjustable(), l.maxUses(), l.usesCount(),
                    l.amountCollected(), l.expiresAt(), l.active(), l.afterCompletion(), l.redirectUrl(),
                    l.successMessage(), l.metadata(), l.qrStyle(),
                    l.lineItems().stream().map(LineItemView::of).toList(), l.createdAt());
        }
    }

    public record LinkList(String object, List<LinkView> data, boolean hasMore) {
    }

    /** What a customer opening the link may see: no merchant id, no usage counters. */
    public record PublicLinkView(String slug, boolean livemode, String merchantName, String type, Long amount,
                                 String currency, Long minAmount, Long maxAmount, String title, String description,
                                 String imageUrl, List<String> collect, boolean quantityAdjustable,
                                 List<LineItemView> lineItems, boolean available, String unavailableReason,
                                 String afterCompletion, String successMessage) {
    }

    public record SessionRequest(@Min(1) @Max(99_999_999) Long amount, @Min(1) @Max(99) Integer quantity,
                                 @Size(max = 254) String email, @Size(max = 160) String name,
                                 @Size(max = 40) String phone, @Size(max = 500) String address) {
    }

    public record SessionCreated(String paymentId, String clientSecret, String url) {
    }

    public record LinkPayment(String paymentId, String reservationId, String status, long amount, Instant createdAt) {
    }

    public record LinkPaymentList(String object, List<LinkPayment> data) {
    }
}
