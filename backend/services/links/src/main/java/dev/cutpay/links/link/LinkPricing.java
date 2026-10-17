package dev.cutpay.links.link;

import dev.cutpay.links.link.Link.LineItem;
import dev.cutpay.web.ApiException;

/** What a customer owes for a link. Pure, so the rules are unit-testable without a database. */
final class LinkPricing {

    static final long MAX_AMOUNT = 99_999_999L;
    static final int MAX_QUANTITY = 99;

    private LinkPricing() {
    }

    /**
     * @param chosenAmount what the customer typed, only for {@code customer_chooses} links
     * @param quantity     how many they want, only when the link lets them adjust it (otherwise it is 1)
     */
    static long total(Link link, Long chosenAmount, Integer quantity) {
        long units = quantity(link, quantity);
        long unitTotal = switch (link.type()) {
            case Link.FIXED -> link.amount();
            case Link.PRODUCTS -> link.lineItems().stream().mapToLong(LinkPricing::lineTotal).sum();
            case Link.CUSTOMER_CHOOSES -> chosen(link, chosenAmount);
            default -> throw new IllegalStateException("Unknown link type " + link.type());
        };
        try {
            long total = Math.multiplyExact(unitTotal, units);
            if (total > MAX_AMOUNT) {
                throw tooLarge();
            }
            return total;
        } catch (ArithmeticException e) {
            throw tooLarge();
        }
    }

    private static long lineTotal(LineItem item) {
        return Math.multiplyExact(item.unitAmount(), (long) item.quantity());
    }

    private static long quantity(Link link, Integer requested) {
        if (!link.quantityAdjustable() || requested == null) {
            return 1;
        }
        if (requested < 1 || requested > MAX_QUANTITY) {
            throw ApiException.invalidRequest("invalid_quantity", "Quantity must be between 1 and " + MAX_QUANTITY,
                    "quantity");
        }
        return requested;
    }

    private static long chosen(Link link, Long amount) {
        if (amount == null) {
            throw ApiException.invalidRequest("amount_required", "Enter the amount you want to pay", "amount");
        }
        if (amount < link.minAmount() || amount > link.maxAmount()) {
            throw ApiException.invalidRequest("amount_out_of_range",
                    "The amount must be between %d and %d (in the smallest currency unit)".formatted(link.minAmount(),
                            link.maxAmount()), "amount");
        }
        return amount;
    }

    private static ApiException tooLarge() {
        return ApiException.invalidRequest("amount_too_large", "The total is above the maximum payment", "amount");
    }
}
