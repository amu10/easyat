package io.github.easyat.core;

import java.io.Serializable;

/**
 * Pluggable encoder/decoder for undo payloads (row images and JDBC parameters).
 *
 * <p>Implementations must be safe to persist and upgrade: old undo logs must still be recoverable
 * after a framework upgrade. The JDBC repository must never rely on Java native serialization for
 * untrusted or long-lived storage.
 */
public interface UndoDataCodec extends Serializable {
    /** 把行镜像编码为字节数组，供 undo 存储持久化。 */
    byte[] encodeRowImage(RowImage image, UndoContext ctx);

    /** 把字节数组解码还原为行镜像（与 {@link #encodeRowImage} 互逆）。 */
    RowImage decodeRowImage(byte[] data, UndoContext ctx);

    /** 把 JDBC 参数数组编码为字节数组，供 undo 存储持久化。 */
    byte[] encodeParameters(Object[] parameters, UndoContext ctx);

    /** 把字节数组解码还原为 JDBC 参数数组（与 {@link #encodeParameters} 互逆）。 */
    Object[] decodeParameters(byte[] data, UndoContext ctx);

    /**
     * Optional human-readable rendering of a row image for diagnostics and the management API.
     * Implementations that support a {@code UndoDataMasker} should suppress sensitive columns here.
     * The default returns {@code null}, in which case callers skip the diagnostic.
     */
    default String toDiagnosticString(RowImage image, UndoContext ctx) {
        return null;
    }
}
