-- v0.1.2 —— 为 easy_at_branch 增加 (xid, resource_id) 唯一约束
--
-- 为什么必须加：空回滚占位、防悬挂、协调端点幂等这三件事都建立在
-- "同一资源在同一全局事务下只有一条分支"这一前提上。此前三个存储都没有强制它，
-- 并发注册理论上能产生重复行，从而让防悬挂检查读到错误的那条分支。
--
-- 适用范围：已在跑的库（CREATE TABLE 里的 UNIQUE 只对新建库生效）。
-- 执行方式：mysql -h host -u user -p db < v0.1.2__branch_unique.sql
--
-- 注意：加唯一索引前必须先去重，否则 ALTER 会直接失败。

-- 1) 先看有没有重复（人工确认用，可跳过）
-- SELECT xid, resource_id, COUNT(*) FROM easy_at_branch GROUP BY xid, resource_id HAVING COUNT(*) > 1;

-- 2) 去重：每个 (xid, resource_id) 只保留 branch_id 字典序最小的那条
DELETE b
FROM easy_at_branch b
JOIN (
    SELECT xid, resource_id, MIN(branch_id) AS keep_id
    FROM easy_at_branch
    GROUP BY xid, resource_id
    HAVING COUNT(*) > 1
) d ON b.xid = d.xid AND b.resource_id = d.resource_id
WHERE b.branch_id <> d.keep_id;

-- 3) 加唯一约束。utf8mb4 下 (128+128)*4 = 1024 字节，低于 InnoDB 3072 字节上限。
ALTER TABLE easy_at_branch
  ADD UNIQUE KEY uk_easy_at_branch_xid_resource (xid, resource_id);
