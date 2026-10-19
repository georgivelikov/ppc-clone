package dev.cutpay.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class KeyPolicyTest {

    @Test
    void secretKeysReachEverythingExceptTeamOnlyRoutes() {
        assertThat(KeyPolicy.allows(List.of("*"), HttpMethod.POST, "/v1/payment_intents")).isTrue();
        assertThat(KeyPolicy.isTeamOnly("/v1/api_keys")).isTrue();
        assertThat(KeyPolicy.isTeamOnly("/v1/api_keys/key_1/roll")).isTrue();
        assertThat(KeyPolicy.isTeamOnly("/v1/merchant")).isTrue();
        assertThat(KeyPolicy.isTeamOnly("/v1/terminal/charges")).isTrue(); // the virtual POS is for people
        assertThat(KeyPolicy.isTeamOnly("/v1/qr")).isTrue();
        assertThat(KeyPolicy.isTeamOnly("/v1/payment_links")).isFalse();
        assertThat(KeyPolicy.isTeamOnly("/me")).isTrue();
        assertThat(KeyPolicy.isTeamOnly("/auth/login")).isTrue();
        assertThat(KeyPolicy.isTeamOnly("/v1/payment_intents")).isFalse();
    }

    @Test
    void restrictedReadScopeAllowsReadsOnly() {
        List<String> scopes = List.of("payments:read");
        assertThat(KeyPolicy.allows(scopes, HttpMethod.GET, "/v1/payment_intents")).isTrue();
        assertThat(KeyPolicy.allows(scopes, HttpMethod.GET, "/v1/payment_intents/pay_1")).isTrue();
        assertThat(KeyPolicy.allows(scopes, HttpMethod.POST, "/v1/payment_intents")).isFalse();
        assertThat(KeyPolicy.allows(scopes, HttpMethod.POST, "/v1/payment_intents/pay_1/cancel")).isFalse();
    }

    @Test
    void writeScopeImpliesRead() {
        List<String> scopes = List.of("refunds:write");
        assertThat(KeyPolicy.allows(scopes, HttpMethod.POST, "/v1/refunds")).isTrue();
        assertThat(KeyPolicy.allows(scopes, HttpMethod.GET, "/v1/refunds")).isTrue();
    }

    @Test
    void paymentLinksHaveTheirOwnScope() {
        assertThat(KeyPolicy.allows(List.of("payment_links:write"), HttpMethod.POST, "/v1/payment_links")).isTrue();
        assertThat(KeyPolicy.allows(List.of("payment_links:read"), HttpMethod.GET, "/v1/payment_links/plink_1/payments"))
                .isTrue();
        assertThat(KeyPolicy.allows(List.of("payment_links:read"), HttpMethod.PATCH, "/v1/payment_links/plink_1"))
                .isFalse();
        assertThat(KeyPolicy.allows(List.of("payments:write"), HttpMethod.POST, "/v1/payment_links")).isFalse();
    }

    @Test
    void bnplSettingsAndExplanationsHaveTheirOwnScope() {
        assertThat(KeyPolicy.allows(List.of("bnpl:write"), HttpMethod.PUT, "/v1/bnpl/settings")).isTrue();
        assertThat(KeyPolicy.allows(List.of("bnpl:read"), HttpMethod.GET, "/v1/bnpl/routing_decisions/pay_1")).isTrue();
        assertThat(KeyPolicy.allows(List.of("bnpl:read"), HttpMethod.PUT, "/v1/bnpl/settings")).isFalse();
        assertThat(KeyPolicy.allows(List.of("billing:write"), HttpMethod.PUT, "/v1/bnpl/settings")).isFalse();
        // The customer's own offers are reachable with the browser-safe key like the rest of the checkout.
        assertThat(KeyPolicy.isPublishableAllowed("/checkout/pay_1_secret_x/bnpl/offers")).isTrue();
    }

    @Test
    void customersAndBillingHaveTheirOwnScopes() {
        assertThat(KeyPolicy.allows(List.of("customers:write"), HttpMethod.POST, "/v1/customers")).isTrue();
        assertThat(KeyPolicy.allows(List.of("customers:read"), HttpMethod.GET, "/v1/customers/cus_1/payment_methods")).isTrue();
        assertThat(KeyPolicy.allows(List.of("customers:write"), HttpMethod.POST, "/v1/setup_intents")).isTrue();
        assertThat(KeyPolicy.allows(List.of("customers:read"), HttpMethod.DELETE, "/v1/customers/cus_1")).isFalse();
        assertThat(KeyPolicy.allows(List.of("billing:write"), HttpMethod.POST, "/v1/subscriptions/sub_1/cancel")).isTrue();
        assertThat(KeyPolicy.allows(List.of("billing:write"), HttpMethod.POST, "/v1/invoices/in_1/pay")).isTrue();
        assertThat(KeyPolicy.allows(List.of("billing:read"), HttpMethod.GET, "/v1/subscription_stats")).isTrue();
        assertThat(KeyPolicy.allows(List.of("billing:read"), HttpMethod.POST, "/v1/products")).isFalse();
        assertThat(KeyPolicy.allows(List.of("payments:write"), HttpMethod.POST, "/v1/subscriptions")).isFalse();
    }

    @Test
    void thePublicInvoicePageIsOpenToTheBrowserSafeKeyButTheInvoiceApiIsNot() {
        assertThat(KeyPolicy.isPublishableAllowed("/invoices/abc")).isTrue();
        assertThat(KeyPolicy.isPublishableAllowed("/v1/invoices")).isFalse();
    }

    @Test
    void restrictedKeysStayInsideTheirResource() {
        List<String> scopes = List.of("payments:write");
        assertThat(KeyPolicy.allows(scopes, HttpMethod.GET, "/v1/payouts")).isFalse();
        assertThat(KeyPolicy.allows(scopes, HttpMethod.GET, "/v1/balance")).isFalse();
        // Conversions and the settlement settings belong to the balance resource.
        assertThat(KeyPolicy.allows(List.of("balance:write"), HttpMethod.POST, "/v1/balance/convert")).isTrue();
        assertThat(KeyPolicy.allows(List.of("balance:read"), HttpMethod.POST, "/v1/fx/quotes")).isFalse();
        assertThat(KeyPolicy.allows(List.of("balance:read"), HttpMethod.GET, "/v1/fx/rates")).isTrue();
        assertThat(KeyPolicy.allows(scopes, HttpMethod.GET, "/v1/currency_settings")).isFalse();
        // Unknown paths are denied rather than allowed by default.
        assertThat(KeyPolicy.allows(scopes, HttpMethod.GET, "/v1/something_new")).isFalse();
        // A longer path that merely starts with a resource name is a different resource.
        assertThat(KeyPolicy.allows(List.of("payouts:read"), HttpMethod.GET, "/v1/payouts_archive")).isFalse();
    }

    @Test
    void publishableKeysAreLimitedToCheckoutAndVault() {
        assertThat(KeyPolicy.isPublishableAllowed("/checkout/sessions/x")).isTrue();
        assertThat(KeyPolicy.isPublishableAllowed("/vault/tokenize")).isTrue();
        assertThat(KeyPolicy.isPublishableAllowed("/links/AbCdEfGhJk")).isTrue();
        assertThat(KeyPolicy.isPublishableAllowed("/v1/payment_intents")).isFalse();
        assertThat(KeyPolicy.isPublishableAllowed("/checkoutx")).isFalse();
    }
}
