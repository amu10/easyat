package io.github.easyat.spring;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 基于 {@code easy-at.gray} 配置的默认稳定分桶实现。 */
public final class PropertiesAtGrayDecider implements AtGrayDecider {
    private static final int BUCKETS = 10000;

    private final EasyAtProperties properties;

    public PropertiesAtGrayDecider(EasyAtProperties properties) {
        this.properties = properties;
    }

    @Override
    public AtGrayDecision decide(AtGrayRequest request) {
        String scene = trim(request.getScene());
        // 未声明灰度场景的旧注解必须保持全量 AT，避免升级后行为改变。
        if (scene == null) return AtGrayDecision.enabled("LEGACY_FULL", -1);

        EasyAtProperties.Gray gray = properties.getGray();
        String mode = normalized(gray.getMode());
        if ("OFF".equals(mode)) return AtGrayDecision.disabled("GLOBAL_OFF", -1);

        EasyAtProperties.GrayRule rule = gray.getRules().get(scene);
        String key = trim(request.getKey());
        if (key != null && rule != null && rule.getBlacklist().contains(key))
            return AtGrayDecision.disabled("BLACKLIST", -1);
        if (key != null && rule != null && rule.getWhitelist().contains(key))
            return AtGrayDecision.enabled("WHITELIST", -1);
        if ("FULL".equals(mode)) return AtGrayDecision.enabled("FULL_MODE", -1);
        if (!"GRAY".equals(mode)) return AtGrayDecision.disabled("INVALID_MODE", -1);
        if (key == null) return AtGrayDecision.disabled("MISSING_KEY", -1);

        int percentage = rule == null ? gray.getDefaultPercentage() : rule.getPercentage();
        String salt = rule == null ? "" : rule.getSalt();
        int bucket = bucket(scene, salt, key);
        return bucket < percentage * 100
                ? AtGrayDecision.enabled("PERCENTAGE_HIT", bucket)
                : AtGrayDecision.disabled("PERCENTAGE_MISS", bucket);
    }

    static int bucket(String scene, String salt, String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes =
                    digest.digest(
                            (scene + ":" + (salt == null ? "" : salt) + ":" + key)
                                    .getBytes(StandardCharsets.UTF_8));
            long value =
                    ((long) (bytes[0] & 0xff) << 24)
                            | ((long) (bytes[1] & 0xff) << 16)
                            | ((long) (bytes[2] & 0xff) << 8)
                            | (long) (bytes[3] & 0xff);
            return (int) (value % BUCKETS);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalized(String mode) {
        String value = trim(mode);
        return value == null ? "FULL" : value.toUpperCase(java.util.Locale.ROOT);
    }
}
