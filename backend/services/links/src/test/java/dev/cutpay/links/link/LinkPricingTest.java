package dev.cutpay.links.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cutpay.links.link.Link.LineItem;
import dev.cutpay.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LinkPricingTest {

    private static Link link(String type, Long amount, Long min, Long max, boolean adjustable, List<LineItem> items) {
        Instant now = Instant.parse("2026-10-18T10:00:00Z");
        return new Link("plink_1", "mer_1", "Rose Cafe", false, "abc", type, amount, "EUR", min, max, "Coffee", null,
                null, List.of(), adjustable, null, 0, 0, 0, null, true, "message", null, null, Map.of(), Map.of(), now,
                now, items);
    }

    @Test
    void aFixedLinkCostsItsAmountTimesTheQuantityTheCustomerPicks() {
        Link fixed = link(Link.FIXED, 450L, null, null, true, List.of());
        assertThat(LinkPricing.total(fixed, null, null)).isEqualTo(450);
        assertThat(LinkPricing.total(fixed, null, 3)).isEqualTo(1350);
    }

    @Test
    void quantityIsIgnoredWhenTheMerchantDidNotAllowAdjusting() {
        Link fixed = link(Link.FIXED, 450L, null, null, false, List.of());
        assertThat(LinkPricing.total(fixed, null, 5)).isEqualTo(450);
    }

    @Test
    void aFixedLinkIgnoresAnAmountTheCustomerTriesToSend() {
        Link fixed = link(Link.FIXED, 450L, null, null, false, List.of());
        assertThat(LinkPricing.total(fixed, 1L, null)).isEqualTo(450);
    }

    @Test
    void aProductsLinkAddsUpItsLines() {
        Link products = link(Link.PRODUCTS, null, null, null, true,
                List.of(new LineItem("Coffee", 350, 2), new LineItem("Cake", 500, 1)));
        assertThat(LinkPricing.total(products, null, null)).isEqualTo(1200);
        assertThat(LinkPricing.total(products, null, 2)).isEqualTo(2400);
    }

    @Test
    void whenTheCustomerChoosesTheAmountMustBeInRange() {
        Link chooses = link(Link.CUSTOMER_CHOOSES, null, 500L, 10_000L, false, List.of());
        assertThat(LinkPricing.total(chooses, 2_500L, null)).isEqualTo(2_500);
        assertThatThrownBy(() -> LinkPricing.total(chooses, null, null)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("amount_required");
        assertThatThrownBy(() -> LinkPricing.total(chooses, 499L, null)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("amount_out_of_range");
        assertThatThrownBy(() -> LinkPricing.total(chooses, 10_001L, null)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("amount_out_of_range");
    }

    @Test
    void aQuantityOutsideOneToNinetyNineIsRefused() {
        Link fixed = link(Link.FIXED, 450L, null, null, true, List.of());
        assertThatThrownBy(() -> LinkPricing.total(fixed, null, 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> LinkPricing.total(fixed, null, 100)).isInstanceOf(ApiException.class);
    }

    @Test
    void aTotalAboveTheMaximumPaymentIsRefusedRatherThanOverflowing() {
        Link big = link(Link.FIXED, 90_000_000L, null, null, true, List.of());
        assertThatThrownBy(() -> LinkPricing.total(big, null, 2)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("amount_too_large");
    }

    @Test
    void anUnavailableLinkSaysWhy() {
        Instant now = Instant.parse("2026-10-18T10:00:00Z");
        Link open = link(Link.FIXED, 450L, null, null, false, List.of());
        assertThat(open.unavailableReason(now)).isNull();
        Link soldOut = new Link(open.id(), open.merchantId(), open.merchantName(), false, open.slug(), open.type(),
                open.amount(), "EUR", null, null, "Coffee", null, null, List.of(), false, 2, 1, 1, 0, null, true,
                "message", null, null, Map.of(), Map.of(), now, now, List.of());
        assertThat(soldOut.unavailableReason(now)).isEqualTo("sold_out");
    }

    @Test
    void slugsAreTenCharactersOfBase58() {
        for (int i = 0; i < 200; i++) {
            assertThat(Slugs.generate()).matches("[1-9A-HJ-NP-Za-km-z]{10}");
        }
    }
}
