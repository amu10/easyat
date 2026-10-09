CREATE TABLE IF NOT EXISTS easy_at_global (
  xid VARCHAR(128) PRIMARY KEY, name VARCHAR(256) NOT NULL, status VARCHAR(32) NOT NULL,
  timeout_at TIMESTAMP NOT NULL, retry_count INTEGER NOT NULL DEFAULT 0, next_retry_at TIMESTAMP NULL,
  version BIGINT NOT NULL DEFAULT 0, owner VARCHAR(128), lease_until TIMESTAMP,
  created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_easy_at_global_recovery ON easy_at_global(status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_easy_at_global_cleanup ON easy_at_global(status, updated_at, xid);
CREATE TABLE IF NOT EXISTS easy_at_undo_log (
  undo_id VARCHAR(128) PRIMARY KEY, xid VARCHAR(128) NOT NULL, resource_id VARCHAR(128) NOT NULL,
  table_name VARCHAR(128) NOT NULL, pk_name VARCHAR(128) NOT NULL, pk_value VARCHAR(512) NOT NULL,
  rollback_sql TEXT NOT NULL, rollback_params BYTEA NOT NULL, before_image BYTEA NULL, after_image BYTEA NULL,
  status VARCHAR(32) NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_easy_at_undo_xid ON easy_at_undo_log(xid);
CREATE TABLE IF NOT EXISTS easy_at_lock (
  resource_id VARCHAR(128) NOT NULL, table_name VARCHAR(128) NOT NULL, pk_value VARCHAR(512) NOT NULL,
  xid VARCHAR(128) NOT NULL, lease_until TIMESTAMP NOT NULL, created_at TIMESTAMP NOT NULL,
  PRIMARY KEY(resource_id, table_name, pk_value)
);
CREATE INDEX IF NOT EXISTS idx_easy_at_lock_xid ON easy_at_lock(xid);
CREATE INDEX IF NOT EXISTS idx_easy_at_lock_expired ON easy_at_lock(lease_until);
CREATE TABLE IF NOT EXISTS easy_at_branch (
  branch_id VARCHAR(128) PRIMARY KEY, xid VARCHAR(128) NOT NULL, resource_id VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL, service_name VARCHAR(128), callback_url VARCHAR(512), sequence INTEGER NOT NULL,
  retry_count INTEGER NOT NULL DEFAULT 0, next_retry_at TIMESTAMP NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_easy_at_branch_xid ON easy_at_branch(xid);
-- 同一资源在同一全局事务下只允许一条分支（空回滚占位 / 防悬挂 / 协调端点幂等的共同前提）。
-- 已存在重复数据的库必须先跑 migration/v0.1.2__branch_unique.sql 去重，否则这条 CREATE 会失败。
CREATE UNIQUE INDEX IF NOT EXISTS uk_easy_at_branch_xid_resource ON easy_at_branch(xid, resource_id);
