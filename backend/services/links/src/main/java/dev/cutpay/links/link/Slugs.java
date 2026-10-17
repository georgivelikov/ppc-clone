package dev.cutpay.links.link;

import java.security.SecureRandom;

/**
 * Short, unguessable link names. Base58 drops the characters people mix up when they read a link aloud or
 * type it from a printed card (0, O, I, l). Ten characters give about 58^10 = 4.3e17 possibilities, so
 * guessing a live link is not practical, and the public endpoint is rate limited on top of that.
 */
final class Slugs {

    static final int LENGTH = 10;
    private static final char[] ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Slugs() {
    }

    static String generate() {
        char[] out = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }
}
