package dev.cutpay.reporting.run;

import dev.cutpay.reporting.config.ReportingProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Download links that carry their own proof: the run, an expiry, and an HMAC of both under a secret only this service knows.
 * Anyone with the link can download until it expires, so it can go in an email; nobody can change the run or the expiry
 * without the signature breaking. (S3 and Google Cloud Storage sign their download links in the same way.)
 */
@Component
public class SignedLinks {

    private final ReportingProperties properties;
    private final Clock clock;

    public SignedLinks(ReportingProperties properties) {
        this.properties = properties;
        this.clock = Clock.systemUTC();
    }

    public record Link(String path, long expiresAt, long expiresIn) {
    }

    public Link create(String runId) {
        long expires = clock.instant().plus(properties.linkTtl()).getEpochSecond();
        return new Link("/v1/reporting/files/" + runId + "?expires=" + expires + "&signature=" + sign(runId, expires), expires,
                properties.linkTtl().toSeconds());
    }

    /** True when the signature is ours and the link has not expired. */
    public boolean valid(String runId, long expires, String signature) {
        if (signature == null || Instant.ofEpochSecond(expires).isBefore(clock.instant())) {
            return false;
        }
        // Compared in constant time, so timing does not reveal how much of a guess was right.
        return MessageDigest.isEqual(sign(runId, expires).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    String sign(String runId, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.signingSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((runId + ":" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
