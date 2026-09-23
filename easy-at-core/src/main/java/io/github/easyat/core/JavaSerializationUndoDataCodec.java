package io.github.easyat.core;

import java.io.*;

/** Legacy fallback codec. Retained only for backward-compatible reads of old undo logs. */
public final class JavaSerializationUndoDataCodec implements UndoDataCodec {
    private static final long serialVersionUID = 1L;

    @Override
    public byte[] encodeRowImage(RowImage image, UndoContext ctx) {
        return serialize(image);
    }

    @Override
    public RowImage decodeRowImage(byte[] data, UndoContext ctx) {
        return (RowImage) deserialize(data);
    }

    @Override
    public byte[] encodeParameters(Object[] parameters, UndoContext ctx) {
        return serialize(parameters);
    }

    @Override
    public Object[] decodeParameters(byte[] data, UndoContext ctx) {
        return (Object[]) deserialize(data);
    }

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

    public static Object deserialize(byte[] value) {
        if (value == null) return null;
        try {
            return new ObjectInputStream(new ByteArrayInputStream(value)).readObject();
        } catch (Exception e) {
            throw new AtException("Cannot deserialize AT value", e);
        }
    }
}
