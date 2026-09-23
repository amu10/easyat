package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the pluggable SPI surface of {@link JacksonUndoDataCodec}: the {@link UndoDataMasker}
 * must suppress sensitive columns in diagnostics, and the {@link UndoDataEncryptor} must store
 * column values encrypted at rest while still round-tripping back to the original image.
 */
class JacksonUndoDataCodecTest {
    private static final UndoContext CTX = new UndoContext("res", "account");

    @Test void maskerHidesSensitiveColumnsInDiagnostics() {
        RowImage image = new RowImage(map("id", 1L, "ssn", "123-45-6789"));
        JacksonUndoDataCodec codec = new JacksonUndoDataCodec(null, new TestMasker());
        String diag = codec.toDiagnosticString(image, CTX);
        assertTrue(diag.contains("\"id\":1"), "non-sensitive column must stay: " + diag);
        assertTrue(diag.contains("\"ssn\":\"***\""), "ssn must be masked: " + diag);
        assertFalse(diag.contains("123-45-6789"), "plain ssn must never appear: " + diag);
    }

    @Test void encryptorRoundTripsRowImage() {
        RowImage image = new RowImage(map("id", 1L, "name", "alice"));
        JacksonUndoDataCodec codec = new JacksonUndoDataCodec(new TestEncryptor());
        byte[] encoded = codec.encodeRowImage(image, CTX);
        String asText = new String(encoded);
        assertTrue(asText.contains("\"@t\":\"enc\""), "column must be stored encrypted: " + asText);
        RowImage decoded = codec.decodeRowImage(encoded, CTX);
        assertEquals(image.getColumns(), decoded.getColumns());
    }

    @Test void nullImageIsSafe() {
        JacksonUndoDataCodec codec = new JacksonUndoDataCodec(new TestEncryptor());
        assertNull(codec.encodeRowImage(null, CTX));
        assertNull(codec.decodeRowImage(null, CTX));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static final class TestMasker implements UndoDataMasker {
        public boolean shouldMask(String r, String t, String c) { return "ssn".equals(c); }
        public String mask(String r, String t, String c, Object v) { return "***"; }
    }

    static final class TestEncryptor implements UndoDataEncryptor {
        public boolean isEnabled() { return true; }
        public byte[] encrypt(String r, String t, String c, byte[] p) {
            byte[] o = new byte[p.length];
            for (int i = 0; i < p.length; i++) o[i] = (byte) (p[i] ^ 0x5A);
            return o;
        }
        public byte[] decrypt(String r, String t, String c, byte[] p) {
            byte[] o = new byte[p.length];
            for (int i = 0; i < p.length; i++) o[i] = (byte) (p[i] ^ 0x5A);
            return o;
        }
    }
}
