package io.github.easyat.core;

/** SPI to encrypt/decrypt sensitive column values at rest in the undo log. */
public interface UndoDataEncryptor {
    /** 是否已启用列级加密（未启用时框架不应调用 encrypt/decrypt，避免无谓开销）。 */
    boolean isEnabled();

    /** 把即将写入 undo 存储的明文字节加密，必须可经 {@link #decrypt} 还原。 */
    byte[] encrypt(String resourceId, String tableName, String column, byte[] plaintext);

    /** 与 {@link #encrypt} 互逆，把密文还原为明文。 */
    byte[] decrypt(String resourceId, String tableName, String column, byte[] cipher);
}
