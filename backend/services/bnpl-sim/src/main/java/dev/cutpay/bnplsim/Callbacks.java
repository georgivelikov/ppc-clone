package dev.cutpay.bnplsim;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Calls a merchant back later, the way an asynchronous provider reports a decision it did not make at once. */
@Component
class Callbacks {

    private static final Logger log = LoggerFactory.getLogger(Callbacks.class);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "bnpl-sim-callback");
        thread.setDaemon(true);
        return thread;
    });
    private final RestClient client;

    Callbacks(RestClient.Builder builder) {
        this.client = builder.build();
    }

    /** Posts a form-encoded body to {@code url} after {@code delay}, signed with {@code secret} in {@code X-Lendo-Signature}. */
    void postForm(String url, Map<String, String> fields, String secret, Duration delay) {
        if (url == null || url.isBlank()) {
            return;
        }
        scheduler.schedule(() -> {
            String body = encode(fields);
            try {
                client.post().uri(url).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Lendo-Signature", sign(secret, body)).body(body).retrieve().toBodilessEntity();
            } catch (RuntimeException e) {
                log.warn("Callback to {} failed: {}", url, e.getMessage());
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Posts JSON to {@code url} after {@code delay}. */
    void postJson(String url, Object body, Duration delay) {
        if (url == null || url.isBlank()) {
            return;
        }
        scheduler.schedule(() -> {
            try {
                client.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
            } catch (RuntimeException e) {
                log.warn("Callback to {} failed: {}", url, e.getMessage());
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    static String encode(Map<String, String> fields) {
        StringBuilder out = new StringBuilder();
        fields.forEach((key, value) -> {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(java.net.URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                    .append(java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
        });
        return out.toString();
    }

    /** HMAC-SHA256 of the exact body, in hex. */
    static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
