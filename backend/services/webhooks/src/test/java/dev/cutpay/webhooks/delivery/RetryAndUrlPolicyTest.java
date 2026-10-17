package dev.cutpay.webhooks.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RetryAndUrlPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    void delaysFollowTheDocumentedSchedule() {
        assertThat(RetrySchedule.delayAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(RetrySchedule.delayAfter(2)).isEqualTo(Duration.ofMinutes(5));
        assertThat(RetrySchedule.delayAfter(3)).isEqualTo(Duration.ofMinutes(30));
        assertThat(RetrySchedule.delayAfter(4)).isEqualTo(Duration.ofHours(2));
        assertThat(RetrySchedule.delayAfter(5)).isEqualTo(Duration.ofHours(5));
        assertThat(RetrySchedule.delayAfter(6)).isEqualTo(Duration.ofHours(10));
    }

    @Test
    void laterAttemptsKeepWaitingTenHours() {
        assertThat(RetrySchedule.delayAfter(7)).isEqualTo(Duration.ofHours(10));
        assertThat(RetrySchedule.delayAfter(50)).isEqualTo(Duration.ofHours(10));
    }

    @Test
    void retriesStopOnceTheNextTryWouldFallAfterTheGiveUpWindow() {
        Instant created = NOW;
        Duration threeDays = Duration.ofDays(3);
        assertThat(RetrySchedule.nextAttempt(1, NOW, created, threeDays)).contains(NOW.plus(Duration.ofMinutes(1)));
        Instant late = created.plus(threeDays).minus(Duration.ofMinutes(30));
        assertThat(RetrySchedule.nextAttempt(6, late, created, threeDays)).isEmpty();
        assertThat(RetrySchedule.nextAttempt(1, late, created, threeDays)).isPresent();
    }

    @Test
    void testModeAllowsLocalhostAndPlainHttp() {
        assertThat(UrlPolicy.problemWith("http://localhost:4000/webhooks", false)).isEmpty();
        assertThat(UrlPolicy.problemWith("http://192.168.1.10/hook", false)).isEmpty();
    }

    @Test
    void liveModeNeedsHttpsAndPublicHosts() {
        assertThat(UrlPolicy.problemWith("https://shop.example.com/hook", true)).isEmpty();
        assertThat(UrlPolicy.problemWith("http://shop.example.com/hook", true)).isPresent();
        assertThat(UrlPolicy.problemWith("https://localhost/hook", true)).isPresent();
        assertThat(UrlPolicy.problemWith("https://127.0.0.1/hook", true)).isPresent();
        assertThat(UrlPolicy.problemWith("https://10.0.0.5/hook", true)).isPresent();
        assertThat(UrlPolicy.problemWith("https://169.254.169.254/latest", true)).isPresent();
        assertThat(UrlPolicy.problemWith("https://[::1]/hook", true)).isPresent();
        assertThat(UrlPolicy.problemWith("https://db.internal/hook", true)).isPresent();
    }

    @Test
    void malformedUrlsAreRejectedInEveryMode() {
        assertThat(UrlPolicy.problemWith("not a url", false)).isPresent();
        assertThat(UrlPolicy.problemWith("ftp://example.com/x", false)).isPresent();
        assertThat(UrlPolicy.problemWith("https://user:pass@example.com/x", false)).isPresent();
        assertThat(UrlPolicy.problemWith("/relative", false)).isPresent();
    }
}
