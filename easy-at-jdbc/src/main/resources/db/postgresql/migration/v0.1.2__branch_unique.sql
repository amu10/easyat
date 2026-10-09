-- v0.1.2 —— 为 easy_at_branch 增加 (xid, resource_id) 唯一约束
--
-- 为什么必须加：空回滚占位、防悬挂、协调端点幂等这三件事都建立在
-- "同一资源在同一全局事务下只有一条分支"这一前提上。
--
-- 适用范围：已在跑的库（建表脚本里的 CREATE UNIQUE INDEX 只对新建库生效）。
-- 执行方式：psql -h host -U user -d db -f v0.1.2__branch_unique.sql
--
-- 注意：加唯一索引前必须先去重，否则 CREATE UNIQUE INDEX 会直接失败。

-- 1) 先看有没有重复（人工确认用，可跳过）
-- SELECT xid, resource_id, COUNT(*) FROM easy_at_branch GROUP BY xid, resource_id HAVING COUNT(*) > 1;

-- 2) 去重：每个 (xid, resource_id) 只保留 branch_id 字典序最小的那条
DELETE FROM easy_at_branch b
USING (
    SELECT xid, resource_id, MIN(branch_id) AS keep_id
    FROM easy_at_branch
    GROUP BY xid, resource_id
    HAVING COUNT(*) > 1
) d
WHERE b.xid = d.xid
  AND b.resource_id = d.resource_id
  AND b.branch_id <> d.keep_id;

-- 3) 加唯一约束
CREATE UNIQUE INDEX IF NOT EXISTS uk_easy_at_branch_xid_resource
  ON easy_at_branch(xid, resource_id);
