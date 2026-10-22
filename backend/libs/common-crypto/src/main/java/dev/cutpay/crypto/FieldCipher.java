package dev.cutpay.crypto;

/**
 * Encrypts one personal value (an IBAN, say) before it is stored and decrypts it when it is read.
 *
 * <p>The {@code context} binds a ciphertext to the thing it belongs to (the merchant, the account): a value copied
 * into another row cannot be decrypted there. Values written before encryption was switched on are plain text, and
 * {@link #decrypt} returns anything that is not a ciphertext as it is, so a table can be migrated gradually.
 */
public interface FieldCipher {

    String encrypt(String plaintext, String context);

    String decrypt(String stored, String context);

    /** True for a value this cipher produced; false for legacy plain text. */
    boolean isEncrypted(String stored);
}
