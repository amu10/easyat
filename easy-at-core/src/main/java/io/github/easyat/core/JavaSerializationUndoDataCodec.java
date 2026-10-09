package io.github.easyat.core;

import java.io.*;

/**
 * 旧的兼容编解码器，仅保留用于向后读取历史遗留的 undo 日志。
 *
 * <p>使用 JDK 原生序列化（{@code ObjectOutputStream}）直接把对象转成字节。 由于原生序列化存在版本脆弱、体积大、易被反序列化攻击等问题，新写入路径不应再使用它，
 * 这里仅保证老数据仍能读出，便于灰度升级。
 */
public final class JavaSerializationUndoDataCodec implements UndoDataCodec {
    private static final long serialVersionUID = 1L;

    /** 把行镜像序列化为字节数组（委托给 {@link #serialize}）。 */
    @Override
    public byte[] encodeRowImage(RowImage image, UndoContext ctx) {
        return serialize(image);
    }

    /** 把字节数组反序列化为行镜像（委托给 {@link #deserialize}）。 */
    @Override
    public RowImage decodeRowImage(byte[] data, UndoContext ctx) {
        return (RowImage) deserialize(data);
    }

    /** 把 JDBC 参数数组序列化为字节数组（委托给 {@link #serialize}）。 */
    @Override
    public byte[] encodeParameters(Object[] parameters, UndoContext ctx) {
        return serialize(parameters);
    }

    /** 把字节数组反序列化为 JDBC 参数数组（委托给 {@link #deserialize}）。 */
    @Override
    public Object[] decodeParameters(byte[] data, UndoContext ctx) {
        return (Object[]) deserialize(data);
    }

    /** 通用序列化：null 直接返回 null，否则用 JDK 原生序列化写进内存字节流。 */
    public static byte[] serialize(Object value) {
        if (value == null) return null;
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            new ObjectOutputStream(b).writeObject(value);
            return b.toByteArray();
        } catch (IOException e) {
            throw new AtException("Cannot serialize AT value", e);
        }
    }

    /** 通用反序列化：null 直接返回 null，否则从内存字节流还原对象。 */
    public static Object deserialize(byte[] value) {
        if (value == null) return null;
        try {
            return new ObjectInputStream(new ByteArrayInputStream(value)).readObject();
        } catch (Exception e) {
            throw new AtException("Cannot deserialize AT value", e);
        }
    }
}
