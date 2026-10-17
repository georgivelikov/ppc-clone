package dev.cutpay.webhooks.delivery;

import dev.cutpay.webhooks.config.WebhookProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Makes the HTTP call for one attempt. Never throws: every outcome comes back as a {@link Result}. */
@Component
public class WebhookSender {

    private static final int SNIPPET_LIMIT = 500;

    /** @param status the HTTP status, or {@code null} when no response arrived (then {@code error} says why) */
    public record Result(Integer status, String snippet, String error, long durationMs) {

        public boolean succeeded() {
            return status != null && status >= 200 && status < 300;
        }
    }

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // A redirect is a failure: following one could move the request somewhere the merchant never chose.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Duration timeout;

    public WebhookSender(WebhookProperties properties) {
        this.timeout = properties.requestTimeout();
    }

    public Result send(String url, String body, String signature, String eventId, boolean livemode) {
        long started = System.nanoTime();
        try {
            URI uri = URI.create(url);
            if (livemode && UrlPolicy.resolvesToInternalAddress(uri.getHost())) {
                return new Result(null, null, "The endpoint resolves to an internal address", elapsed(started));
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Cutpay-Webhooks/1.0")
                    .header("Cutpay-Event-Id", eventId)
                    .header("Cutpay-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                String snippet = new String(in.readNBytes(SNIPPET_LIMIT), StandardCharsets.UTF_8);
                return new Result(response.statusCode(), snippet, null, elapsed(started));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(null, null, "Interrupted", elapsed(started));
        } catch (IOException | RuntimeException e) {
            return new Result(null, null, describe(e), elapsed(started));
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static long elapsed(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }
}
