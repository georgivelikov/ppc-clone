package dev.cutpay.identity.apikey;

import dev.cutpay.identity.domain.ApiKey;
import dev.cutpay.identity.domain.ApiKey.Type;
import dev.cutpay.identity.domain.Repositories.ApiKeyRepository;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates, rolls, revokes and resolves API keys (docs/adr/0009-api-keys.md). */
@Service
public class ApiKeyService {

    private static final char[] BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int SECRET_LENGTH = 32;
    private static final int PREFIX_LENGTH = 12;
    private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Resources a restricted key can be limited to; each takes {@code :read} or {@code :write}. */
    static final Set<String> RESOURCES =
            Set.of("payments", "refunds", "balance", "payouts", "bank_accounts", "webhooks", "payment_links");

    private final ApiKeyRepository keys;

    public ApiKeyService(ApiKeyRepository keys) {
        this.keys = keys;
    }

    public record Created(ApiKey key, String secret) {
    }

    @Transactional
    public Created create(String merchantId, String createdBy, String name, boolean livemode, Type type,
                          List<String> requestedScopes) {
        List<String> scopes = switch (type) {
            case SECRET -> List.of("*");
            case PUBLISHABLE -> List.of();
            case RESTRICTED -> validatedScopes(requestedScopes);
        };
        return issue(merchantId, createdBy, name, livemode, type, scopes);
    }

    /** Issues a replacement with the same settings; the old key keeps working for the grace period. */
    @Transactional
    public Created roll(String merchantId, String keyId, String createdBy, Duration grace) {
        ApiKey old = find(merchantId, keyId);
        if (!old.isUsable(Instant.now())) {
            throw ApiException.invalidRequest("api_key_inactive", "Only active keys can be rolled", null);
        }
        Created created = issue(merchantId, createdBy, old.getName(), old.isLivemode(), old.getType(),
                old.getScopes());
        if (grace.isZero()) {
            old.revoke();
        } else {
            old.expireAt(Instant.now().plus(grace));
        }
        return created;
    }

    @Transactional
    public void revoke(String merchantId, String keyId) {
        find(merchantId, keyId).revoke();
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(String merchantId) {
        return keys.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    /** Looks a presented key up by hash. Unknown, revoked and expired keys are all just "not found". */
    @Transactional
    public Optional<ApiKey> authenticate(String rawKey) {
        Instant now = Instant.now();
        Optional<ApiKey> found = keys.findByKeyHash(hash(rawKey)).filter(key -> key.isUsable(now));
        found.ifPresent(key -> {
            // Throttled so a busy key does not turn every request into a write.
            if (key.getLastUsedAt() == null || key.getLastUsedAt().isBefore(now.minus(LAST_USED_RESOLUTION))) {
                key.touch(now);
            }
        });
        return found;
    }

    private Created issue(String merchantId, String createdBy, String name, boolean livemode, Type type,
                          List<String> scopes) {
        String secret = generate(type, livemode);
        ApiKey key = new ApiKey(Ids.generate("key"), merchantId, name, livemode, type,
                secret.substring(0, PREFIX_LENGTH), hash(secret), secret.substring(secret.length() - 4), scopes,
                createdBy);
        return new Created(keys.save(key), secret);
    }

    private ApiKey find(String merchantId, String keyId) {
        return keys.findByIdAndMerchantId(keyId, merchantId)
                .orElseThrow(() -> ApiException.notFound("api_key_not_found", "API key not found"));
    }

    private static List<String> validatedScopes(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw ApiException.invalidRequest("scopes_required", "A restricted key needs at least one scope",
                    "scopes");
        }
        for (String scope : requested) {
            String[] parts = scope.split(":", -1);
            boolean valid = parts.length == 2 && RESOURCES.contains(parts[0])
                    && (parts[1].equals("read") || parts[1].equals("write"));
            if (!valid) {
                throw ApiException.invalidRequest("invalid_scope", "Unknown scope: " + scope, "scopes");
            }
        }
        return requested.stream().distinct().toList();
    }

    static String generate(Type type, boolean livemode) {
        StringBuilder out = new StringBuilder(type.prefix()).append(livemode ? "_live_" : "_test_");
        for (int i = 0; i < SECRET_LENGTH; i++) {
            out.append(BASE62[RANDOM.nextInt(BASE62.length)]);
        }
        return out.toString();
    }

    /** Keys are 190 bits of randomness, so an unsalted fast hash is enough; there is nothing to brute-force. */
    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
