-- easyAt 影子运行对账（MySQL）
--
-- 投产前连续 ≥7 天、每天至少跑一次。任何一项返回行数 > 0 都需要处理（阈值与处理流程见 RUNBOOK.md）。
-- 用法：mysql -h host -u user -p db < reconciliation.sql

SELECT '--- 1) 残留事务：ACTIVE 已超时仍未收敛 ---' AS section;
SELECT xid, name, status, timeout_at, retry_count, owner, lease_until
FROM easy_at_global
WHERE status = 'ACTIVE' AND timeout_at < NOW() - INTERVAL 1 MINUTE
ORDER BY timeout_at;

SELECT '--- 2) 卡住的回滚：ROLLING_BACK 且没有有效租约在推进 ---' AS section;
SELECT xid, name, status, retry_count, owner, lease_until, next_retry_at
FROM easy_at_global
WHERE status = 'ROLLING_BACK' AND (lease_until IS NULL OR lease_until < NOW())
ORDER BY xid;

SELECT '--- 3) COMMITTING 黑洞：本地已提交但全局未收敛 ---' AS section;
SELECT xid, name, status, retry_count, next_retry_at, owner
FROM easy_at_global
WHERE status = 'COMMITTING'
ORDER BY xid;

SELECT '--- 4) 需人工介入（MANUAL_INTERVENTION / DIRTY_WRITE / 回滚失败）---' AS section;
SELECT xid, name, status, retry_count, next_retry_at
FROM easy_at_global
WHERE status IN ('MANUAL_INTERVENTION', 'DIRTY_WRITE', 'ROLLBACK_FAILED')
ORDER BY status, xid;

SELECT '--- 5) 锁泄漏：锁还在，但所属事务已终态或已不存在 ---' AS section;
SELECT l.resource_id, l.table_name, l.pk_value, l.xid, l.lease_until, g.status AS global_status
FROM easy_at_lock l
LEFT JOIN easy_at_global g ON g.xid = l.xid
WHERE g.xid IS NULL
   OR g.status IN ('COMMITTED', 'ROLLED_BACK', 'MANUAL_INTERVENTION')
ORDER BY l.xid;

SELECT '--- 6) 分支悬挂（跨服务）：分支已终态但全局事务仍在途 ---' AS section;
SELECT b.xid, b.branch_id, b.resource_id, b.status AS branch_status, g.status AS global_status
FROM easy_at_branch b
JOIN easy_at_global g ON g.xid = b.xid
WHERE g.status IN ('ACTIVE', 'COMMITTING')
  AND b.status IN ('COMMITTED', 'ROLLED_BACK')
ORDER BY b.xid;

SELECT '--- 7) 汇总计数（直接用于告警）---' AS section;
SELECT
  SUM(status = 'ACTIVE'   AND timeout_at < NOW() - INTERVAL 1 MINUTE) AS active_timed_out,
  SUM(status = 'ROLLING_BACK' AND (lease_until IS NULL OR lease_until < NOW())) AS rolling_back_stuck,
  SUM(status = 'COMMITTING')     AS committing_stuck,
  SUM(status = 'ROLLBACK_FAILED') AS rollback_failed,
  SUM(status = 'DIRTY_WRITE')     AS dirty_write,
  SUM(status = 'MANUAL_INTERVENTION') AS manual_intervention,
  COUNT(*) AS total
FROM easy_at_global;
