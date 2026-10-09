package io.github.easyat.core;

/** Carries the storage location of an undo payload so codecs can apply encrypt/mask SPI. */
/** Carries the storage location of an undo payload so codecs can apply encrypt/mask SPI. */
public final class UndoContext {
    /** 资源 id：加解密/脱敏 SPI 据此区分不同数据源的密钥与策略。 */
    private final String resourceId;

    /** 表名：脱敏规则通常按表维度配置。 */
    private final String tableName;

    public UndoContext(String resourceId, String tableName) {
        this.resourceId = resourceId;
        this.tableName = tableName;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getTableName() {
        return tableName;
    }
}
