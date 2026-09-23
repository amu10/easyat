CREATE TABLE IF NOT EXISTS easy_at_global (
  xid VARCHAR(128) PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  status VARCHAR(32) NOT NULL,
  timeout_at DATETIME(3) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NULL DEFAULT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  owner VARCHAR(128) NULL,
  lease_until DATETIME(3) NULL DEFAULT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  INDEX idx_easy_at_global_recovery (status, next_retry_at),
  INDEX idx_easy_at_global_cleanup (status, updated_at, xid)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS easy_at_undo_log (
  undo_id VARCHAR(128) PRIMARY KEY,
  xid VARCHAR(128) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  pk_name VARCHAR(128) NOT NULL,
  pk_value VARCHAR(512) NOT NULL,
  rollback_sql TEXT NOT NULL,
  rollback_params LONGBLOB NOT NULL,
  before_image LONGBLOB NULL,
  after_image LONGBLOB NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  INDEX idx_easy_at_undo_xid (xid)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS easy_at_lock (
  resource_id VARCHAR(128) NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  pk_value VARCHAR(512) NOT NULL,
  xid VARCHAR(128) NOT NULL,
  lease_until DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY(resource_id, table_name, pk_value),
  INDEX idx_easy_at_lock_xid (xid),
  INDEX idx_easy_at_lock_expired (lease_until)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS easy_at_branch (
  branch_id VARCHAR(128) PRIMARY KEY,
  xid VARCHAR(128) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  service_name VARCHAR(128),
  callback_url VARCHAR(512),
  sequence INT NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NULL DEFAULT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  INDEX idx_easy_at_branch_xid (xid)
) ENGINE=InnoDB;
