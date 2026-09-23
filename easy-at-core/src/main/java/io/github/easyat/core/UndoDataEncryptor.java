package io.github.easyat.core;

/** SPI to encrypt/decrypt sensitive column values at rest in the undo log. */
public interface UndoDataEncryptor {
    boolean isEnabled();
    /** Encrypt raw bytes destined for the undo store. Must be reversible via {@link #decrypt}. */
    byte[] encrypt(String resourceId, String tableName, String column, byte[] plaintext);
    /** Reverse {@link #encrypt}. */
    byte[] decrypt(String resourceId, String tableName, String column, byte[] cipher);
}
