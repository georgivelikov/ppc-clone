package dev.cutpay.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaymentMethodRulesTest {

    @Test
    void cardsAndWalletsAreOfferedInEveryCurrency() {
        for (String currency : new String[] {"EUR", "RON", "PLN", "HUF", "CZK", "GBP", "USD", "CHF"}) {
            assertThat(PaymentMethodRules.available(currency, "automatic")).contains("card", "apple_pay", "google_pay");
        }
    }

    @Test
    void blikIsOfferedOnlyForPlnPaymentsTakenAtOnce() {
        assertThat(PaymentMethodRules.available("PLN", "automatic")).contains("blik");
        assertThat(PaymentMethodRules.available("EUR", "automatic")).doesNotContain("blik");
        assertThat(PaymentMethodRules.available("PLN", "manual")).doesNotContain("blik").contains("card");
    }
}
