package io.github.easyat.spring;

/** 灰度决策结果；不包含原始灰度 Key，避免业务标识进入日志或指标。 */
public final class AtGrayDecision {
    private final boolean enabled;
    private final String reason;
    private final int bucket;

    private AtGrayDecision(boolean enabled, String reason, int bucket) {
        this.enabled = enabled;
        this.reason = reason;
        this.bucket = bucket;
    }

    public static AtGrayDecision enabled(String reason, int bucket) {
        return new AtGrayDecision(true, reason, bucket);
    }

    public static AtGrayDecision disabled(String reason, int bucket) {
        return new AtGrayDecision(false, reason, bucket);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getReason() {
        return reason;
    }

    public int getBucket() {
        return bucket;
    }
}
