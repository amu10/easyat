package io.github.easyat.jdbc;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.easyat.core.*;
import java.io.*;
import java.math.*;
import java.sql.*;
import java.util.*;
import java.util.Base64;
import javax.sql.rowset.serial.SerialBlob;

/**
 * Versioned JSON codec for undo payloads. Replaces Java native serialization so that the stored
 * format is both human-inspectable and forward/backward compatible. Unknown value types fall back
 * to Java serialization wrapped in a tagged node, and legacy Java-serialized blobs are still
 * readable for upgrade safety.
 */
public final class JacksonUndoDataCodec implements UndoDataCodec {
    private static final long serialVersionUID = 1L;
    private static final int VERSION = 1;
    private final ObjectMapper mapper = new ObjectMapper();
    private final UndoDataEncryptor encryptor;
    private final UndoDataMasker masker;

    public JacksonUndoDataCodec() {
        this(null, null);
    }

    public JacksonUndoDataCodec(UndoDataEncryptor encryptor) {
        this(encryptor, null);
    }

    public JacksonUndoDataCodec(UndoDataEncryptor encryptor, UndoDataMasker masker) {
        this.encryptor = encryptor;
        this.masker = masker;
    }

    @Override
    public byte[] encodeRowImage(RowImage image, UndoContext ctx) {
        if (image == null) return null;
        ObjectNode root = mapper.createObjectNode();
        root.put("v", VERSION);
        ObjectNode cols = root.putObject("cols");
        for (Map.Entry<String, Object> e : image.getColumns().entrySet())
            cols.set(e.getKey(), encodeValue(e.getValue(), e.getKey(), ctx));
        return write(root);
    }

    @Override
    public RowImage decodeRowImage(byte[] data, UndoContext ctx) {
        if (data == null) return null;
        if (isJavaSerialization(data))
            return (RowImage) JavaSerializationUndoDataCodec.deserialize(data);
        try {
            JsonNode root = mapper.readTree(data);
            ObjectNode cols = (ObjectNode) root.get("cols");
            Map<String, Object> values = new LinkedHashMap<String, Object>();
            Iterator<Map.Entry<String, JsonNode>> it = cols.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                values.put(f.getKey(), decodeValue(f.getValue(), ctx));
            }
            return new RowImage(values);
        } catch (IOException e) {
            throw new AtException("Cannot decode row image", e);
        }
    }

    @Override
    public byte[] encodeParameters(Object[] params, UndoContext ctx) {
        ArrayNode arr = mapper.createArrayNode();
        if (params != null) for (Object p : params) arr.add(encodeValue(p, null, ctx));
        ObjectNode root = mapper.createObjectNode();
        root.put("v", VERSION);
        root.set("p", arr);
        return write(root);
    }

    @Override
    public Object[] decodeParameters(byte[] data, UndoContext ctx) {
        if (data == null) return null;
        if (isJavaSerialization(data))
            return (Object[]) JavaSerializationUndoDataCodec.deserialize(data);
        try {
            JsonNode root = mapper.readTree(data);
            ArrayNode arr = (ArrayNode) root.get("p");
            Object[] out = new Object[arr.size()];
            for (int i = 0; i < arr.size(); i++) out[i] = decodeValue(arr.get(i), ctx);
            return out;
        } catch (IOException e) {
            throw new AtException("Cannot decode parameters", e);
        }
    }

    /**
     * Renders a row image for human inspection. Columns flagged by the optional {@link
     * UndoDataMasker} are replaced with their masked representation, so sensitive values never
     * surface in logs or the management API. Columns are otherwise rendered using the same
     * type-aware JSON encoding as storage (but without encryption).
     */
    public String toDiagnosticString(RowImage image, UndoContext ctx) {
        ObjectNode root = mapper.createObjectNode();
        if (image != null) {
            for (Map.Entry<String, Object> e : image.getColumns().entrySet()) {
                String col = e.getKey();
                Object val = e.getValue();
                if (masker != null
                        && masker.shouldMask(ctx.getResourceId(), ctx.getTableName(), col))
                    root.put(col, masker.mask(ctx.getResourceId(), ctx.getTableName(), col, val));
                else root.set(col, toNode(val));
            }
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new AtException("Cannot render undo diagnostic", e);
        }
    }

    private JsonNode encodeValue(Object value, String column, UndoContext ctx) {
        JsonNode node = toNode(value);
        // 列级加密：开启时把密文以 {@code {"@t":"enc","@v":base64,"@c":列名}} 标签节点写入 JSON，
        // 解密时据此还原明文。加密在落库时进行，明文不出现在存储层。
        if (encryptor != null && encryptor.isEnabled() && column != null) {
            try {
                byte[] plain = mapper.writeValueAsBytes(node);
                byte[] cipher =
                        encryptor.encrypt(ctx.getResourceId(), ctx.getTableName(), column, plain);
                ObjectNode enc = mapper.createObjectNode();
                enc.put("@t", "enc");
                enc.put("@v", Base64.getEncoder().encodeToString(cipher));
                enc.put("@c", column);
                return enc;
            } catch (IOException e) {
                throw new AtException("Cannot encrypt undo value", e);
            }
        }
        return node;
    }

    private Object decodeValue(JsonNode node, UndoContext ctx) {
        if (node instanceof ObjectNode && node.has("@t") && "enc".equals(node.get("@t").asText())) {
            String col = node.has("@c") ? node.get("@c").asText() : null;
            byte[] cipher = Base64.getDecoder().decode(node.get("@v").asText());
            byte[] plain = encryptor.decrypt(ctx.getResourceId(), ctx.getTableName(), col, cipher);
            try {
                return fromNode(mapper.readTree(plain));
            } catch (IOException e) {
                throw new AtException("Cannot decrypt undo value", e);
            }
        }
        return fromNode(node);
    }

    private JsonNode toNode(Object v) {
        if (v == null) return NullNode.getInstance();
        if (v instanceof String) return TextNode.valueOf((String) v);
        if (v instanceof Character) return TextNode.valueOf(v.toString());
        if (v instanceof Boolean) return BooleanNode.valueOf((Boolean) v);
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte)
            return LongNode.valueOf(((Number) v).longValue());
        if (v instanceof Double || v instanceof Float)
            return DoubleNode.valueOf(((Number) v).doubleValue());
        if (v instanceof BigDecimal) return tagged("bd", ((BigDecimal) v).toString());
        if (v instanceof BigInteger) return tagged("bi", ((BigInteger) v).toString());
        if (v instanceof byte[])
            return tagged("bin", Base64.getEncoder().encodeToString((byte[]) v));
        if (v instanceof Timestamp) return tagged("ts", Long.toString(((Timestamp) v).getTime()));
        if (v instanceof java.sql.Date)
            return tagged("dt", Long.toString(((java.sql.Date) v).getTime()));
        if (v instanceof Time) return tagged("tm", Long.toString(((Time) v).getTime()));
        if (v instanceof java.util.Date)
            return tagged("ud", Long.toString(((java.util.Date) v).getTime()));
        if (v instanceof Blob) {
            try {
                return tagged("blob", Base64.getEncoder().encodeToString(readBytes((Blob) v)));
            } catch (SQLException e) {
                throw new AtException("Cannot read blob", e);
            }
        }
        if (v instanceof Clob) {
            try {
                return tagged("clob", readString((Clob) v));
            } catch (SQLException e) {
                throw new AtException("Cannot read clob", e);
            }
        }
        // 兜底：无法识别的类型退回 Java 原生序列化（@t=java）。安全注意：Java 反序列化对
        // 不可信输入是 RCE 面，生产应保证 undo 数据只来自受信存储层，或替换为白名单化方案。
        return tagged(
                "java",
                Base64.getEncoder().encodeToString(JavaSerializationUndoDataCodec.serialize(v)));
    }

    private Object fromNode(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n instanceof ObjectNode && n.has("@t")) {
            String t = n.get("@t").asText();
            String v = n.get("@v").asText();
            switch (t) {
                case "bd":
                    return new BigDecimal(v);
                case "bi":
                    return new BigInteger(v);
                case "bin":
                    return Base64.getDecoder().decode(v);
                case "ts":
                    return new Timestamp(Long.parseLong(v));
                case "dt":
                    return new java.sql.Date(Long.parseLong(v));
                case "tm":
                    return new Time(Long.parseLong(v));
                case "ud":
                    return new java.util.Date(Long.parseLong(v));
                case "blob":
                    try {
                        return new SerialBlob(Base64.getDecoder().decode(v));
                    } catch (SQLException e) {
                        throw new AtException("Cannot rebuild blob", e);
                    }
                case "clob":
                    return v;
                case "java":
                    return JavaSerializationUndoDataCodec.deserialize(
                            Base64.getDecoder().decode(v));
                default:
                    throw new AtException("Unknown undo value tag: " + t);
            }
        }
        if (n.isTextual()) return n.asText();
        if (n.isBoolean()) return n.asBoolean();
        if (n.isLong() || n.isInt()) return n.asLong();
        if (n.isDouble() || n.isFloat()) return n.asDouble();
        if (n.isBigInteger()) return n.bigIntegerValue();
        if (n.isBigDecimal()) return n.decimalValue();
        if (n.isNumber()) return n.numberValue();
        return n.asText();
    }

    private static ObjectNode tagged(String t, String v) {
        ObjectNode n = new ObjectMapper().createObjectNode();
        n.put("@t", t);
        n.put("@v", v);
        return n;
    }

    private byte[] write(JsonNode node) {
        try {
            return mapper.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new AtException("Cannot encode undo payload", e);
        }
    }

    private static boolean isJavaSerialization(byte[] data) {
        return data != null
                && data.length > 2
                && (data[0] & 0xFF) == 0xac
                && (data[1] & 0xFF) == 0xed;
    }

    private static byte[] readBytes(Blob b) throws SQLException {
        try (InputStream in = b.getBinaryStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        } catch (IOException e) {
            throw new AtException("Cannot read blob", e);
        }
    }

    private static String readString(Clob c) throws SQLException {
        try (Reader r = c.getCharacterStream()) {
            StringBuilder s = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) s.append(buf, 0, n);
            return s.toString();
        } catch (IOException e) {
            throw new AtException("Cannot read clob", e);
        }
    }
}
