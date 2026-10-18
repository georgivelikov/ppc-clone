package dev.cutpay.bnpl.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.cutpay.bnpl.config.BnplProperties;
import dev.cutpay.bnpl.model.Applicant;
import dev.cutpay.bnpl.model.ApplicationRequest;
import dev.cutpay.bnpl.model.ProviderException;
import dev.cutpay.bnpl.model.ProviderException.Kind;
import dev.cutpay.bnpl.model.ProviderResult;
import dev.cutpay.bnpl.model.ProviderResult.Outcome;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The second half of the adapter contract: applying, asking where an application stands, closing it, and for Lendo
 * believing a callback. As in {@link AdapterContractTest}, the answers are what bnpl-sim returns.
 */
class ApplicationContractTest {

    static WireMockServer provider = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static BnplProperties properties;

    @BeforeAll
    static void start() {
        provider.start();
        String base = provider.baseUrl();
        properties = new BnplProperties("http://unused", Duration.ofSeconds(2), Duration.ofMillis(600), "BG", "http://localhost:8091",
                Duration.ofSeconds(5), Duration.ofHours(24), Duration.ofDays(1), List.of(6, 12, 24),
                new BnplProperties.Providers(base + "/splitnow/v1", base + "/lendo/api", base + "/kreditko", base + "/paylatereu/api",
                        "lendo_test_secret"));
    }

    @AfterAll
    static void stop() {
        provider.stop();
    }

    @BeforeEach
    void reset() {
        provider.resetAll();
    }

    private static final Applicant ANNA = new Applicant("anna@example.com", "Anna Petrova", "1990-01-15", "AB123456", 300_000L);

    private static ApplicationRequest application(String plan, int count) {
        return new ApplicationRequest("bnpl_1", 30_000, "EUR", plan, count, ANNA);
    }

    private static void json(MappingBuilder mapping, int status, String body) {
        provider.stubFor(mapping.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private SplitNowAdapter splitnow() {
        return new SplitNowAdapter(properties, RestClient.builder());
    }

    private LendoAdapter lendo() {
        return new LendoAdapter(properties, RestClient.builder());
    }

    private KreditKoAdapter kreditko() {
        return new KreditKoAdapter(properties, RestClient.builder());
    }

    private PayLaterEuAdapter paylater() {
        return new PayLaterEuAdapter(properties, RestClient.builder());
    }

    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Kind kind) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }

    // --- what each provider needs to know ----------------------------------------------------------------------

    @Test
    void eachProviderAsksForWhatItNeedsAndNothingMore() {
        assertThat(splitnow().requiredFields()).containsExactly("email");
        assertThat(paylater().requiredFields()).containsExactly("email");
        assertThat(lendo().requiredFields()).containsExactly("email", "birthdate");
        assertThat(kreditko().requiredFields()).containsExactly("email", "national_id", "monthly_income");
        assertThat(new Applicant("a@b.co", null, null, null, null).missing(kreditko().requiredFields()))
                .containsExactly("national_id", "monthly_income");
        assertThat(new Applicant("nope", null, null, null, 0L).missing(kreditko().requiredFields()))
                .containsExactly("email", "national_id", "monthly_income");
        assertThat(ANNA.missing(kreditko().requiredFields())).isEmpty();
    }

    // --- SplitNow ----------------------------------------------------------------------------------------------

    @Test
    void splitNowAnswersAnApplicationAtOnce() {
        json(post("/splitnow/v1/checkouts"), 201, "{\"id\":\"sn_1\",\"state\":\"approved\"}");
        assertThat(splitnow().createApplication(application("splitnow_3", 3))).isEqualTo(ProviderResult.approved("sn_1"));
        provider.verify(postRequestedFor(urlEqualTo("/splitnow/v1/checkouts")).withRequestBody(equalToJson(
                "{\"amount_cents\":30000,\"currency\":\"EUR\",\"instalments\":3,\"reference\":\"bnpl_1\","
                        + "\"customer\":{\"email\":\"anna@example.com\",\"name\":\"Anna Petrova\"}}")));

        json(post("/splitnow/v1/checkouts"), 201, "{\"id\":\"sn_2\",\"state\":\"declined\",\"decline_reason\":\"risk_check_failed\"}");
        assertThat(splitnow().createApplication(application("splitnow_3", 3))).isEqualTo(ProviderResult.declined("sn_2", "risk_check_failed"));

        json(get("/splitnow/v1/checkouts/sn_2"), 200, "{\"id\":\"sn_2\",\"state\":\"canceled\"}");
        assertThat(splitnow().status("sn_2")).isEqualTo(ProviderResult.canceled("sn_2"));

        json(post("/splitnow/v1/checkouts/sn_2/cancel"), 200, "{\"id\":\"sn_2\",\"state\":\"canceled\"}");
        splitnow().cancel("sn_2");
        provider.verify(postRequestedFor(urlEqualTo("/splitnow/v1/checkouts/sn_2/cancel")));

        json(post("/splitnow/v1/checkouts"), 201, "{\"id\":\"sn_3\",\"state\":\"maybe\"}");
        assertFailure(() -> splitnow().createApplication(application("splitnow_3", 3)), Kind.INVALID_RESPONSE);
        json(post("/splitnow/v1/checkouts"), 422, "{\"error\":{\"type\":\"amount_out_of_range\"}}");
        assertFailure(() -> splitnow().createApplication(application("splitnow_3", 3)), Kind.REFUSED);
    }

    // --- Lendo -------------------------------------------------------------------------------------------------

    @Test
    void lendoTakesAFormAnswersProcessingAndCanBeAskedLater() {
        json(post("/lendo/api/applications"), 202, "{\"application_id\":\"ld_1\",\"status\":\"processing\"}");
        assertThat(lendo().createApplication(application("lendo_12", 12))).isEqualTo(ProviderResult.pending("ld_1"));
        provider.verify(postRequestedFor(urlEqualTo("/lendo/api/applications"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("amount=300.00")).withRequestBody(containing("months=12"))
                .withRequestBody(containing("birthdate=1990-01-15")).withRequestBody(containing("reference=bnpl_1"))
                .withRequestBody(containing("callback_url=http%3A%2F%2Flocalhost%3A8091%2Fbnpl%2Fcallback%2Flendo")));

        json(get(urlPathEqualTo("/lendo/api/applications/ld_1")), 200, "{\"application_id\":\"ld_1\",\"status\":\"accepted\",\"score\":700}");
        assertThat(lendo().status("ld_1")).isEqualTo(ProviderResult.approved("ld_1"));
        json(get(urlPathEqualTo("/lendo/api/applications/ld_1")), 200, "{\"application_id\":\"ld_1\",\"status\":\"rejected\"}");
        assertThat(lendo().status("ld_1")).isEqualTo(ProviderResult.declined("ld_1", "credit_check_failed"));
        json(get(urlPathEqualTo("/lendo/api/applications/ld_1")), 200, "{\"application_id\":\"ld_1\",\"status\":\"processing\"}");
        assertThat(lendo().status("ld_1")).isEqualTo(ProviderResult.pending("ld_1"));

        json(post("/lendo/api/applications/ld_1/cancel"), 200, "{\"status\":\"canceled\"}");
        lendo().cancel("ld_1");
        provider.verify(postRequestedFor(urlEqualTo("/lendo/api/applications/ld_1/cancel")));
    }

    private static String lendoSignature(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("lendo_test_secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void lendoCallbacksAreBelievedOnlyWithTheRightSignatureOverTheExactBody() throws Exception {
        String body = "application_id=ld_1&outcome=accepted&score=700&reference=bnpl_1";

        var callback = lendo().handleCallback(Map.of("x-lendo-signature", lendoSignature(body)), body).orElseThrow();
        assertThat(callback.reference()).isEqualTo("bnpl_1");
        assertThat(callback.result()).isEqualTo(ProviderResult.approved("ld_1"));

        String rejected = "application_id=ld_1&outcome=rejected&score=400&reference=bnpl_1";
        assertThat(lendo().handleCallback(Map.of("x-lendo-signature", lendoSignature(rejected)), rejected).orElseThrow().result().outcome())
                .isEqualTo(Outcome.DECLINED);

        assertThatThrownBy(() -> lendo().handleCallback(Map.of(), body)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> lendo().handleCallback(Map.of("x-lendo-signature", "00"), body)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> lendo().handleCallback(Map.of("x-lendo-signature", lendoSignature(body)), body + "&x=1"))
                .isInstanceOf(SecurityException.class);

        String incomplete = "outcome=accepted";
        assertFailure(() -> lendo().handleCallback(Map.of("x-lendo-signature", lendoSignature(incomplete)), incomplete), Kind.INVALID_RESPONSE);
        String strange = "application_id=ld_1&outcome=weird&reference=bnpl_1";
        assertFailure(() -> lendo().handleCallback(Map.of("x-lendo-signature", lendoSignature(strange)), strange), Kind.INVALID_RESPONSE);

        // The other providers never call back.
        assertThat(splitnow().handleCallback(Map.of(), "x")).isEmpty();
        assertThat(kreditko().handleCallback(Map.of(), "x")).isEmpty();
        assertThat(paylater().handleCallback(Map.of(), "x")).isEmpty();
    }

    // --- refunds -----------------------------------------------------------------------------------------------

    @Test
    void eachProviderIsRefundedInItsOwnUnitsAndShape() {
        json(post("/splitnow/v1/checkouts/sn_1/refunds"), 201, "{\"id\":\"sn_1\",\"refunded_cents\":10050}");
        splitnow().refund("sn_1", 10_050);
        provider.verify(postRequestedFor(urlEqualTo("/splitnow/v1/checkouts/sn_1/refunds")).withRequestBody(equalToJson("{\"amount_cents\":10050}")));

        json(post("/lendo/api/applications/ld_1/refund"), 201, "{\"application_id\":\"ld_1\",\"refunded\":\"100.50\"}");
        lendo().refund("ld_1", 10_050);
        provider.verify(postRequestedFor(urlEqualTo("/lendo/api/applications/ld_1/refund"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded")).withRequestBody(containing("amount=100.50")));

        json(post("/kreditko/loans/KK-1/refunds"), 201, "{\"loanId\":\"KK-1\",\"refundedMinor\":10050}");
        kreditko().refund("KK-1", 10_050);
        provider.verify(postRequestedFor(urlEqualTo("/kreditko/loans/KK-1/refunds")).withRequestBody(equalToJson("{\"amountMinor\":10050}")));

        json(post("/paylatereu/api"), 200, "{\"ok\":true,\"code\":\"OK\",\"data\":{\"authId\":\"PL1\",\"refunded\":100.50}}");
        paylater().refund("PL1", 10_050);
        provider.verify(postRequestedFor(urlEqualTo("/paylatereu/api")).withRequestBody(equalToJson(
                "{\"action\":\"refund\",\"authId\":\"PL1\",\"amount\":100.50}", true, true)));
    }

    @Test
    void aRefundTheProviderRefusesIsToldApartFromOneThatCouldNotBeAsked() {
        json(post("/splitnow/v1/checkouts/sn_1/refunds"), 422, "{\"error\":{\"type\":\"refund_too_large\"}}");
        assertFailure(() -> splitnow().refund("sn_1", 1), Kind.REFUSED);
        json(post("/kreditko/loans/KK-1/refunds"), 409, "{\"code\":\"NOT_REFUNDABLE\"}");
        assertFailure(() -> kreditko().refund("KK-1", 1), Kind.REFUSED);
        json(post("/lendo/api/applications/ld_1/refund"), 422, "{\"error\":\"refund_too_large\"}");
        assertFailure(() -> lendo().refund("ld_1", 1), Kind.REFUSED);
        json(post("/paylatereu/api"), 200, "{\"ok\":false,\"code\":\"REFUND_TOO_LARGE\",\"data\":null}");
        assertFailure(() -> paylater().refund("PL1", 1), Kind.REFUSED);

        json(post("/lendo/api/applications/ld_1/refund"), 503, "{}");
        assertFailure(() -> lendo().refund("ld_1", 1), Kind.UNAVAILABLE);
        json(post("/splitnow/v1/checkouts/sn_1/refunds"), 504, "{}");
        assertFailure(() -> splitnow().refund("sn_1", 1), Kind.TIMEOUT);
    }

    // --- KreditKo ----------------------------------------------------------------------------------------------

    @Test
    void kreditKoSendsNestedCamelCaseAndIsAskedAboutLoansUnderReview() {
        json(post("/kreditko/loans"), 201, "{\"loanId\":\"KK-1\",\"status\":\"UNDER_REVIEW\"}");
        assertThat(kreditko().createApplication(application("kreditko_12", 12))).isEqualTo(ProviderResult.pending("KK-1"));
        provider.verify(postRequestedFor(urlEqualTo("/kreditko/loans")).withRequestBody(equalToJson("""
                {"amountMinor":30000,"currency":"EUR","termMonths":12,"reference":"bnpl_1",
                 "applicant":{"nationalId":"AB123456","monthlyIncomeMinor":300000,"email":"anna@example.com"}}""")));

        json(get("/kreditko/loans/KK-1"), 200, "{\"loanId\":\"KK-1\",\"status\":\"APPROVED\"}");
        assertThat(kreditko().status("KK-1")).isEqualTo(ProviderResult.approved("KK-1"));
        json(get("/kreditko/loans/KK-1"), 200, "{\"loanId\":\"KK-1\",\"status\":\"REJECTED\",\"reason\":\"insufficient_income\"}");
        assertThat(kreditko().status("KK-1")).isEqualTo(ProviderResult.declined("KK-1", "insufficient_income"));

        json(post("/kreditko/loans"), 400, "{\"code\":\"VALIDATION\"}");
        assertFailure(() -> kreditko().createApplication(application("kreditko_12", 12)), Kind.REFUSED);
        json(post("/kreditko/loans/KK-1/cancel"), 200, "{}");
        kreditko().cancel("KK-1");
        provider.verify(postRequestedFor(urlEqualTo("/kreditko/loans/KK-1/cancel")));
    }

    // --- PayLaterEU --------------------------------------------------------------------------------------------

    @Test
    void payLaterEuAuthorisesThroughTheSameRpcEndpointAndTellsADeclineFromAFailure() {
        json(post("/paylatereu/api"), 200, "{\"ok\":true,\"code\":\"OK\",\"data\":{\"authId\":\"PL1\",\"state\":\"AUTHORISED\"}}");
        assertThat(paylater().createApplication(application("paylatereu_30", 1))).isEqualTo(ProviderResult.approved("PL1"));
        provider.verify(postRequestedFor(urlEqualTo("/paylatereu/api")).withRequestBody(equalToJson(
                "{\"action\":\"authorise\",\"amount\":300.00,\"currency\":\"EUR\",\"customerEmail\":\"anna@example.com\","
                        + "\"reference\":\"bnpl_1\"}", true, true)));

        json(post("/paylatereu/api"), 200, "{\"ok\":false,\"code\":\"DECLINED\",\"data\":{\"authId\":\"PL2\",\"state\":\"DECLINED\"}}");
        assertThat(paylater().createApplication(application("paylatereu_30", 1))).isEqualTo(ProviderResult.declined("PL2", "declined_by_provider"));

        json(post("/paylatereu/api"), 200, "{\"ok\":false,\"code\":\"AMOUNT_RANGE\",\"data\":null}");
        assertFailure(() -> paylater().createApplication(application("paylatereu_30", 1)), Kind.REFUSED);

        json(post("/paylatereu/api"), 200, "{\"ok\":true,\"code\":\"OK\",\"data\":{\"authId\":\"PL1\",\"state\":\"CANCELED\"}}");
        assertThat(paylater().status("PL1")).isEqualTo(ProviderResult.canceled("PL1"));
        paylater().cancel("PL1");
        provider.verify(postRequestedFor(urlEqualTo("/paylatereu/api")).withRequestBody(containing("\"action\":\"cancel\"")));

        json(post("/paylatereu/api"), 200, "{\"ok\":false,\"code\":\"NOT_CANCELABLE\",\"data\":null}");
        assertFailure(() -> paylater().cancel("PL1"), Kind.REFUSED);
    }
}
