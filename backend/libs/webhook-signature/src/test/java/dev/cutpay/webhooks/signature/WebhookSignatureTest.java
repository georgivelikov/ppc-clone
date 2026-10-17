package dev.cutpay.webhooks.signature;

import static dev.cutpay.webhooks.signature.WebhookSignature.Result.MALFORMED_HEADER;
import static dev.cutpay.webhooks.signature.WebhookSignature.Result.NO_MATCHING_SIGNATURE;
import static dev.cutpay.webhooks.signature.WebhookSignature.Result.TIMESTAMP_OUT_OF_RANGE;
import static dev.cutpay.webhooks.signature.WebhookSignature.Result.VALID;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

    private static final String SECRET = "whsec_test_secret";
    private static final String BODY = "{\"id\":\"evt_1\",\"type\":\"payout.paid\"}";
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    void aFreshCorrectlySignedBodyIsValid() {
        String header = WebhookSignature.sign(SECRET, BODY, NOW);
        assertThat(header).matches("t=\\d+,v1=[0-9a-f]{64}");
        assertThat(WebhookSignature.verify(SECRET, BODY, header, NOW)).isEqualTo(VALID);
    }

    @Test
    void theSignatureIsHmacSha256OfTimestampDotBody() {
        // Known value, so other languages can check their implementation against this one:
        //   printf '1760000000.{}' | openssl dgst -sha256 -hmac secret
        assertThat(WebhookSignature.sign("secret", "{}", Instant.ofEpochSecond(1_760_000_000L)))
                .isEqualTo("t=1760000000,v1=53dc054739ad94d3532227ba8d397f66ed2166a6b66940c2006692fa6db6829f");
    }

    @Test
    void aTamperedBodyFails() {
        String header = WebhookSignature.sign(SECRET, BODY, NOW);
        assertThat(WebhookSignature.verify(SECRET, BODY.replace("paid", "failed"), header, NOW))
                .isEqualTo(NO_MATCHING_SIGNATURE);
    }

    @Test
    void theWrongSecretFails() {
        String header = WebhookSignature.sign(SECRET, BODY, NOW);
        assertThat(WebhookSignature.verify("whsec_other", BODY, header, NOW)).isEqualTo(NO_MATCHING_SIGNATURE);
    }

    @Test
    void anOldTimestampIsRejectedEvenWhenTheSignatureMatches() {
        String header = WebhookSignature.sign(SECRET, BODY, NOW.minus(Duration.ofMinutes(5)).minusSeconds(1));
        assertThat(WebhookSignature.verify(SECRET, BODY, header, NOW)).isEqualTo(TIMESTAMP_OUT_OF_RANGE);
    }

    @Test
    void theToleranceBoundaryIsInclusive() {
        String header = WebhookSignature.sign(SECRET, BODY, NOW.minus(Duration.ofMinutes(5)));
        assertThat(WebhookSignature.verify(SECRET, BODY, header, NOW)).isEqualTo(VALID);
    }

    @Test
    void aTimestampFromTheFutureIsRejectedToo() {
        String header = WebhookSignature.sign(SECRET, BODY, NOW.plus(Duration.ofMinutes(10)));
        assertThat(WebhookSignature.verify(SECRET, BODY, header, NOW)).isEqualTo(TIMESTAMP_OUT_OF_RANGE);
    }

    @Test
    void movingTheTimestampInvalidatesTheSignature() {
        String signed = WebhookSignature.sign(SECRET, BODY, NOW.minus(Duration.ofHours(1)));
        String signature = signed.substring(signed.indexOf("v1="));
        String forged = "t=" + NOW.getEpochSecond() + "," + signature;
        assertThat(WebhookSignature.verify(SECRET, BODY, forged, NOW)).isEqualTo(NO_MATCHING_SIGNATURE);
    }

    @Test
    void malformedHeadersAreReportedAsSuch() {
        assertThat(WebhookSignature.verify(SECRET, BODY, null, NOW)).isEqualTo(MALFORMED_HEADER);
        assertThat(WebhookSignature.verify(SECRET, BODY, "", NOW)).isEqualTo(MALFORMED_HEADER);
        assertThat(WebhookSignature.verify(SECRET, BODY, "v1=abc", NOW)).isEqualTo(MALFORMED_HEADER);
        assertThat(WebhookSignature.verify(SECRET, BODY, "t=abc,v1=abc", NOW)).isEqualTo(MALFORMED_HEADER);
        assertThat(WebhookSignature.verify(SECRET, BODY, "t=" + NOW.getEpochSecond(), NOW))
                .isEqualTo(MALFORMED_HEADER);
        assertThat(WebhookSignature.verify(SECRET, BODY, "garbage", NOW)).isEqualTo(MALFORMED_HEADER);
    }

    @Test
    void anyOneOfSeveralSignaturesIsEnoughSoSecretsCanBeRotated() {
        String oldHeader = WebhookSignature.sign("whsec_old", BODY, NOW);
        String newHeader = WebhookSignature.sign(SECRET, BODY, NOW);
        String both = newHeader + "," + oldHeader.substring(oldHeader.indexOf("v1="));
        assertThat(WebhookSignature.verify(List.of("whsec_old"), BODY, both, NOW, Duration.ofMinutes(5)))
                .isEqualTo(VALID);
        assertThat(WebhookSignature.verify(List.of(SECRET), BODY, both, NOW, Duration.ofMinutes(5)))
                .isEqualTo(VALID);
    }
}
