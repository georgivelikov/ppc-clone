package dev.cutpay.webhooks.signature;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and verifies webhook requests.
 *
 * <p>The {@code Cutpay-Signature} header looks like {@code t=1760000000,v1=<hex>}, where {@code v1} is
 * {@code HMAC-SHA256(secret, t + "." + body)}. Signing the timestamp together with the body means a captured
 * request cannot be replayed later: {@link #verify} rejects timestamps outside a tolerance window (five
 * minutes by default). A header may carry several {@code v1} values, so a secret can be rotated without
 * dropping deliveries; any one match is enough.
 *
 * <p>The class has no dependencies so a merchant's server (or the demo shop) can copy it as is.
 */
public final class WebhookSignature {

    public static final String HEADER = "Cutpay-Signature";
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    public enum Result {
        VALID, MALFORMED_HEADER, TIMESTAMP_OUT_OF_RANGE, NO_MATCHING_SIGNATURE
    }

    private WebhookSignature() {
    }

    /** Builds the header value for {@code body}, signed at {@code timestamp}. */
    public static String sign(String secret, String body, Instant timestamp) {
        long seconds = timestamp.getEpochSecond();
        return "t=" + seconds + ",v1=" + hmac(secret, seconds + "." + body);
    }

    public static Result verify(String secret, String body, String header, Instant now) {
        return verify(List.of(secret), body, header, now, DEFAULT_TOLERANCE);
    }

    /** Checks against several candidate secrets (e.g. current and previous) at once. */
    public static Result verify(List<String> secrets, String body, String header, Instant now, Duration tolerance) {
        if (header == null || body == null) {
            return Result.MALFORMED_HEADER;
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                return Result.MALFORMED_HEADER;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (key.equals("t")) {
                try {
                    timestamp = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    return Result.MALFORMED_HEADER;
                }
            } else if (key.equals("v1")) {
                signatures.add(value);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            return Result.MALFORMED_HEADER;
        }
        // Checked before the signature so a stale request is reported as stale, but it must also be
        // signed correctly to count as valid: an attacker cannot move the timestamp.
        long age = now.getEpochSecond() - timestamp;
        if (Math.abs(age) > tolerance.toSeconds()) {
            return Result.TIMESTAMP_OUT_OF_RANGE;
        }
        for (String secret : secrets) {
            byte[] expected = hmac(secret, timestamp + "." + body).getBytes(StandardCharsets.UTF_8);
            for (String candidate : signatures) {
                if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8))) {
                    return Result.VALID;
                }
            }
        }
        return Result.NO_MATCHING_SIGNATURE;
    }

    private static String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
