package dev.cutpay.gateway.apikey;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Exchanges an API key for the key's identity plus a short-lived JWT, by asking the identity service.
 *
 * <p>Results are cached for a few seconds, so a revoked key stops working within {@link #CACHE_TTL}. The cache
 * is keyed by the key's hash so raw keys are not kept as map keys. Unknown keys are cached too, which keeps a
 * flood of bad keys away from identity. Transport errors are not cached.
 */
@Component
public class ApiKeyResolver {

    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    public record Resolution(boolean valid, String keyId, String merchantId, boolean livemode, String type,
                             List<String> scopes, String token) {
        static final Resolution INVALID = new Resolution(false, null, null, false, null, List.of(), null);
    }

    /** Identity answers in snake_case like the rest of the API. */
    record Response(@JsonProperty("key_id") String keyId, @JsonProperty("merchant_id") String merchantId,
                    boolean livemode, String type, List<String> scopes, String token) {
    }

    record KeyRequest(String key) {
    }

    private final WebClient identity;
    private final AsyncCache<String, Resolution> cache =
            Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(CACHE_TTL).buildAsync();

    public ApiKeyResolver(@Value("${cutpay.upstreams.identity}") String identityUrl) {
        this.identity = WebClient.builder().baseUrl(identityUrl).build();
    }

    public Mono<Resolution> resolve(String rawKey) {
        String hash = sha256(rawKey);
        // Failed lookups are evicted before the error reaches the caller, so the next request retries identity.
        return Mono.fromFuture(() -> cache.get(hash,
                (h, executor) -> fetch(rawKey).doOnError(e -> cache.synchronous().invalidate(h)).toFuture()));
    }

    private Mono<Resolution> fetch(String rawKey) {
        return identity.post().uri("/internal/api_keys/authenticate")
                .bodyValue(new KeyRequest(rawKey))
                .retrieve()
                .bodyToMono(Response.class)
                .map(r -> new Resolution(true, r.keyId(), r.merchantId(), r.livemode(), r.type(), r.scopes(),
                        r.token()))
                .onErrorResume(WebClientResponseException.class, e ->
                        e.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)
                                ? Mono.just(Resolution.INVALID)
                                : Mono.error(e));
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
