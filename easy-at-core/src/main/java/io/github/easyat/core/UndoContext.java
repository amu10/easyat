package io.github.easyat.core;
/** Carries the storage location of an undo payload so codecs can apply encrypt/mask SPI. */
public final class UndoContext {
    private final String resourceId; private final String tableName;
    public UndoContext(String resourceId,String tableName){this.resourceId=resourceId;this.tableName=tableName;}
    public String getResourceId(){return resourceId;} public String getTableName(){return tableName;}
}
