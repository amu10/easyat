-- 混合存储（easy-at.storage.type=hybrid）专用：业务库只需要这一张 undo 表。
--
-- 为什么要拆出来：混合模式下全局事务状态、分支、全局锁都在 Redis，
-- 业务库只负责 undo log——因为它必须与业务 DML 在同一个本地事务里提交。
-- 不要把 easy-at.sql 整套建到业务库里，多余的表反而会让人误以为那是数据源。
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
