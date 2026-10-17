package dev.cutpay.payments.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Makes {@code POST /v1/**} safe to retry. A client that sends the same {@code Idempotency-Key} twice gets
 * the first response replayed instead of creating a second payment.
 *
 * <ul>
 *   <li>First use: the key is claimed in the database <em>before</em> the work happens, so two concurrent
 *       requests with one key cannot both run.</li>
 *   <li>Same key and same request: the stored status and body are replayed.</li>
 *   <li>Same key but a different request: 409, because that is a client bug worth surfacing.</li>
 *   <li>Same key while the first request is still running: 409.</li>
 *   <li>5xx responses are not stored, so the client can retry them.</li>
 *   <li>Keys expire after 24 hours.</li>
 * </ul>
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    private static final int MAX_KEY_LENGTH = 255;

    private record StoredKey(String requestHash, Integer responseStatus, String responseBody) {
    }

    private final JdbcClient jdbc;

    public IdempotencyFilter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!"POST".equals(request.getMethod()) || key == null || !request.getRequestURI().startsWith("/v1/")
                || !(auth instanceof JwtAuthenticationToken jwt)) {
            chain.doFilter(request, response);
            return;
        }
        if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            writeError(response, 400, "invalid_request_error", "invalid_idempotency_key",
                    "Idempotency-Key must be 1 to %d characters".formatted(MAX_KEY_LENGTH));
            return;
        }

        // Test and live traffic of one merchant must not share keys, or a retry in one mode could replay the other's response.
        String merchantId = jwt.getToken().getClaimAsString("mid")
                + (Boolean.TRUE.equals(jwt.getToken().getClaimAsBoolean("livemode")) ? "/live" : "/test");
        var cached = new CachedBodyRequest(request);
        String hash = hash(request.getMethod(), request.getRequestURI(), request.getQueryString(), cached.body());

        if (!claim(merchantId, key, hash)) {
            StoredKey stored = load(merchantId, key).orElse(null);
            if (stored == null) {
                // The row vanished between claim and load (expired and cleaned up): treat as in progress.
                writeError(response, 409, "invalid_request_error", "idempotency_in_progress",
                        "A request with this Idempotency-Key is still being processed");
            } else if (!stored.requestHash().equals(hash)) {
                writeError(response, 409, "invalid_request_error", "idempotency_key_reuse",
                        "This Idempotency-Key was already used with a different request");
            } else if (stored.responseStatus() == null) {
                writeError(response, 409, "invalid_request_error", "idempotency_in_progress",
                        "A request with this Idempotency-Key is still being processed");
            } else {
                response.setStatus(stored.responseStatus());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader("Idempotent-Replayed", "true");
                response.getOutputStream().write(stored.responseBody().getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        var wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(cached, wrapped);
        } catch (RuntimeException | ServletException | IOException e) {
            release(merchantId, key);
            throw e;
        }
        if (wrapped.getStatus() >= 500) {
            release(merchantId, key);
        } else {
            complete(merchantId, key, wrapped.getStatus(),
                    new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8));
        }
        wrapped.copyBodyToResponse();
    }

    @Scheduled(fixedDelayString = "PT1H")
    void deleteExpired() {
        jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < now() - interval '24 hours'").update();
    }

    /** True when this request now owns the key (new, or an expired one that was reset). */
    private boolean claim(String merchantId, String key, String hash) {
        return jdbc.sql("""
                        INSERT INTO idempotency_keys (merchant_id, key, request_hash)
                        VALUES (:merchant, :key, :hash)
                        ON CONFLICT (merchant_id, key) DO UPDATE
                           SET request_hash = EXCLUDED.request_hash, response_status = NULL,
                               response_body = NULL, created_at = now()
                           WHERE idempotency_keys.created_at < now() - interval '24 hours'
                        """)
                .param("merchant", merchantId).param("key", key).param("hash", hash)
                .update() == 1;
    }

    private Optional<StoredKey> load(String merchantId, String key) {
        return jdbc.sql("""
                        SELECT request_hash, response_status, response_body FROM idempotency_keys
                        WHERE merchant_id = :merchant AND key = :key
                        """)
                .param("merchant", merchantId).param("key", key)
                .query((rs, n) -> new StoredKey(rs.getString("request_hash"),
                        (Integer) rs.getObject("response_status"), rs.getString("response_body")))
                .optional();
    }

    private void complete(String merchantId, String key, int status, String body) {
        jdbc.sql("""
                        UPDATE idempotency_keys SET response_status = :status, response_body = :body
                        WHERE merchant_id = :merchant AND key = :key
                        """)
                .param("status", status).param("body", body).param("merchant", merchantId).param("key", key)
                .update();
    }

    private void release(String merchantId, String key) {
        jdbc.sql("DELETE FROM idempotency_keys WHERE merchant_id = :merchant AND key = :key")
                .param("merchant", merchantId).param("key", key).update();
    }

    private static void writeError(HttpServletResponse response, int status, String type, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":{\"type\":\"%s\",\"code\":\"%s\",\"message\":\"%s\"}}"
                .formatted(type, code, message));
    }

    private static String hash(String method, String uri, String query, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((method + " " + uri + "?" + (query == null ? "" : query) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lets the body be read once for hashing and again by the controller. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        byte[] body() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return source.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
