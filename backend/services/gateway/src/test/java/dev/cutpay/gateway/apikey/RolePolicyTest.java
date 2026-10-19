package dev.cutpay.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** The permission matrix, one row per thing a role should or should not be able to do. */
class RolePolicyTest {

    private static boolean can(String role, HttpMethod method, String path) {
        return RolePolicy.allows(role, method, path);
    }

    @Test
    void ownersAndAdminsCanDoEverything() {
        for (String role : new String[] {"owner", "admin"}) {
            assertThat(can(role, HttpMethod.POST, "/v1/refunds")).isTrue();
            assertThat(can(role, HttpMethod.POST, "/v1/team/invites")).isTrue();
            assertThat(can(role, HttpMethod.POST, "/v1/payouts")).isTrue();
            assertThat(can(role, HttpMethod.PATCH, "/v1/merchant")).isTrue();
        }
    }

    @Test
    void viewersReadButNeverChange() {
        assertThat(can("viewer", HttpMethod.GET, "/v1/payment_intents")).isTrue();
        assertThat(can("viewer", HttpMethod.GET, "/v1/balance")).isTrue();
        assertThat(can("viewer", HttpMethod.POST, "/v1/refunds")).isFalse();
        assertThat(can("viewer", HttpMethod.POST, "/v1/payment_links")).isFalse();
        assertThat(can("viewer", HttpMethod.POST, "/v1/balance/convert")).isFalse();
        assertThat(can("viewer", HttpMethod.POST, "/v1/payouts")).isFalse();
    }

    @Test
    void viewersAndDevelopersCanAskForReportsButSupportCannot() {
        assertThat(can("viewer", HttpMethod.POST, "/v1/reporting/report_runs")).isTrue();
        assertThat(can("developer", HttpMethod.POST, "/v1/reporting/report_runs")).isTrue();
        assertThat(can("viewer", HttpMethod.GET, "/v1/reporting/overview")).isTrue();
        assertThat(can("support", HttpMethod.GET, "/v1/reporting/overview")).isFalse();
    }

    @Test
    void viewersDoNotSeeKeysOrTheTeam() {
        assertThat(can("viewer", HttpMethod.GET, "/v1/api_keys")).isFalse();
        assertThat(can("viewer", HttpMethod.GET, "/v1/team/members")).isFalse();
        assertThat(can("viewer", HttpMethod.GET, "/v1/audit_events")).isFalse();
    }

    @Test
    void supportCanRefundAndHelpCustomersButNotMoveMoney() {
        assertThat(can("support", HttpMethod.POST, "/v1/refunds")).isTrue();
        assertThat(can("support", HttpMethod.GET, "/v1/payment_intents/pay_1")).isTrue();
        assertThat(can("support", HttpMethod.POST, "/v1/customers")).isTrue();
        assertThat(can("support", HttpMethod.POST, "/v1/payouts")).isFalse();
        assertThat(can("support", HttpMethod.GET, "/v1/balance")).isFalse();
        assertThat(can("support", HttpMethod.POST, "/v1/payment_intents")).isFalse();
        assertThat(can("support", HttpMethod.GET, "/v1/api_keys")).isFalse();
    }

    @Test
    void developersBuildButDoNotMoveMoneyOrManageTheTeam() {
        assertThat(can("developer", HttpMethod.POST, "/v1/api_keys")).isTrue();
        assertThat(can("developer", HttpMethod.POST, "/v1/webhook_endpoints")).isTrue();
        assertThat(can("developer", HttpMethod.POST, "/v1/payment_links")).isTrue();
        assertThat(can("developer", HttpMethod.GET, "/v1/logs")).isTrue();
        assertThat(can("developer", HttpMethod.POST, "/v1/refunds")).isFalse();
        assertThat(can("developer", HttpMethod.POST, "/v1/payouts")).isFalse();
        assertThat(can("developer", HttpMethod.PUT, "/v1/bank_accounts/ba_1")).isFalse();
        assertThat(can("developer", HttpMethod.GET, "/v1/team/members")).isFalse();
        assertThat(can("developer", HttpMethod.PATCH, "/v1/merchant")).isFalse();
    }

    @Test
    void apiKeyTokensAreLeftToTheKeyScopes() {
        assertThat(can("api", HttpMethod.POST, "/v1/refunds")).isTrue();
    }

    @Test
    void requestsWithoutARoleAreNotThisFiltersBusiness() {
        assertThat(can(null, HttpMethod.GET, "/checkout/sessions/x")).isTrue();
    }

    @Test
    void anUnknownRoleGetsNothingKnownAndUnknownRoutesAreReadOnly() {
        assertThat(can("intern", HttpMethod.GET, "/v1/payment_intents")).isFalse();
        assertThat(can("viewer", HttpMethod.GET, "/v1/something_new")).isTrue();
        assertThat(can("viewer", HttpMethod.POST, "/v1/something_new")).isFalse();
    }

    @Test
    void ownSecurityAndEmailChoicesAreOpenToEveryMember() {
        assertThat(can("viewer", HttpMethod.POST, "/v1/security/totp/setup")).isTrue();
        assertThat(can("support", HttpMethod.PUT, "/v1/notification_preferences")).isTrue();
        assertThat(can("viewer", HttpMethod.GET, "/v1/notifications")).isFalse(); // the log is for the team's managers
    }

    @Test
    void sessionRoutesAreOpenToEveryMember() {
        assertThat(can("viewer", HttpMethod.GET, "/me")).isTrue();
        assertThat(can("support", HttpMethod.POST, "/auth/logout")).isTrue();
    }
}
