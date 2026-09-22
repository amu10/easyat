CREATE TABLE IF NOT EXISTS easy_at_global (
  xid VARCHAR(128) PRIMARY KEY, name VARCHAR(256) NOT NULL, status VARCHAR(32) NOT NULL,
  timeout_at TIMESTAMP NOT NULL, retry_count INTEGER NOT NULL DEFAULT 0, next_retry_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_easy_at_global_recovery ON easy_at_global(status, next_retry_at);
CREATE TABLE IF NOT EXISTS easy_at_undo_log (
  undo_id VARCHAR(128) PRIMARY KEY, xid VARCHAR(128) NOT NULL, resource_id VARCHAR(128) NOT NULL,
  table_name VARCHAR(128) NOT NULL, pk_name VARCHAR(128) NOT NULL, pk_value BYTEA NOT NULL,
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
