package io.github.easyat.core;

import java.io.Serializable;

/**
 * 一条 undo（补偿）记录：描述「如何把某一行还原到 before image」。
 *
 * <p>回滚时由 {@link UndoExecutor} 执行 {@link #rollbackSql}；执行前先做脏写校验， 比对 {@link
 * #afterImage}（业务执行后的整行）与数据库当前行，不等则拒绝覆盖。
 */
public final class UndoRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 一条 undo 的五个定位字段：记录 id（UUID）、所属全局事务 xid、资源 id、 目标表名、主键列名。它们一起唯一定位「哪条事务、对哪张表的哪一行」做补偿。 */
    private final String id, xid, resourceId, tableName, primaryKeyColumn;

    /** 主键的具体值（字符串或数字），是回滚时定位行的条件。 */
    private final Object primaryKeyValue;

    /** 回滚用的补偿 SQL：UPDATE 整行（除主键）、DELETE 整行（把行插回），或 INSERT 的按主键 DELETE。 */
    private final String rollbackSql;

    /** 绑定到 rollbackSql 的参数值（业务 DML 的原始参数，按文本顺序）。 */
    private final Object[] parameters;

    /** 业务 DML 执行<b>前</b>的整行快照，回滚要还原到的目标。 */
    private RowImage beforeImage;

    /** 业务 DML 执行<b>后</b>的整行快照，用于回滚前的脏写校验。 */
    private RowImage afterImage;

    /** 该 undo 是否已被执行回滚（控制幂等，避免恢复重试时重复补偿同一行）。 */
    private boolean rolledBack;

    public UndoRecord(
            String id,
            String xid,
            String resourceId,
            String tableName,
            String pk,
            Object pkValue,
            String sql,
            Object[] parameters) {
        this.id = id;
        this.xid = xid;
        this.resourceId = resourceId;
        this.tableName = tableName;
        this.primaryKeyColumn = pk;
        this.primaryKeyValue = pkValue;
        this.rollbackSql = sql;
        this.parameters = parameters == null ? new Object[0] : parameters.clone();
    }

    public String getId() {
        return id;
    }

    public String getXid() {
        return xid;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getTableName() {
        return tableName;
    }

    public String getPrimaryKeyColumn() {
        return primaryKeyColumn;
    }

    public Object getPrimaryKeyValue() {
        return primaryKeyValue;
    }

    public String getRollbackSql() {
        return rollbackSql;
    }

    public Object[] getParameters() {
        return parameters.clone();
    }

    public boolean isRolledBack() {
        return rolledBack;
    }

    public void markRolledBack() {
        rolledBack = true;
    }

    public RowImage getBeforeImage() {
        return beforeImage;
    }

    public void setBeforeImage(RowImage image) {
        beforeImage = image;
    }

    public RowImage getAfterImage() {
        return afterImage;
    }

    public void setAfterImage(RowImage image) {
        afterImage = image;
    }
}
