package dev.cutpay.web;

import java.security.SecureRandom;
import java.time.Clock;

/**
 * Generates Stripe-style prefixed identifiers such as {@code mer_01J9ZK3Q8V2M6X4N7B5C1D0E9F}.
 *
 * <p>The suffix is a ULID: 48 bits of millisecond timestamp followed by 80 random bits, encoded as
 * 26 Crockford base32 characters. IDs sort by creation time, which keeps B-tree inserts cheap
 * and makes cursor pagination trivial.
 */
public final class Ids {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String generate(String prefix) {
        return generate(prefix, Clock.systemUTC());
    }

    public static String generate(String prefix, Clock clock) {
        return prefix + "_" + ulid(clock.millis());
    }

    static String ulid(long timestampMillis) {
        char[] out = new char[26];
        long time = timestampMillis;
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        // 80 random bits -> 16 base32 characters, consumed 5 bits at a time.
        int bitBuffer = 0;
        int bitCount = 0;
        int index = 10;
        for (byte b : random) {
            bitBuffer = (bitBuffer << 8) | (b & 0xFF);
            bitCount += 8;
            while (bitCount >= 5) {
                out[index++] = ALPHABET[(bitBuffer >>> (bitCount - 5)) & 31];
                bitCount -= 5;
            }
            bitBuffer &= (1 << bitCount) - 1;
        }
        return new String(out);
    }
}
