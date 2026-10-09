package io.github.easyat.storage.redis;

import io.github.easyat.core.AtException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Redis 侧的 terminate-state 清理器。
 *
 * <h2>为什么必须存在</h2>
 *
 * <p>Redis 存储先前<b>没有任何回收手段</b>：终态事务的 {@code easy-at:global:<xid>}、undo 实体 hash、
 * 分支记录、状态集合成员全都不会被删除，也没有 TTL。结果是键只增不减，几天就能撑爆内存； 真撞上 {@code maxmemory} 又配了 {@code
 * allkeys-lru}，被淘汰的可能正是尚未收敛事务的 undo—— 那不报错，是静默地无法回滚。
 *
 * <h2>两条防线</h2>
 *
 * <ol>
 *   <li><b>主动清理</b>（本类）：靠 {@code easy-at:cleanup:index} 这个 zset（member=xid、score=最后更新时间） 按时间捞候选，只删
 *       COMMITTED / ROLLED_BACK 且已超过保留期的事务。
 *   <li><b>兜底 TTL</b>：所有键在<b>每次被写入时</b>刷新一次 EXPIRE（见 {@link RedisAtRepository}），
 *       所以"有人在推进的事务"永远不会到期；只有彻底无人处理、清理器也挂了的孤儿键才最终由 Redis 回收。
 * </ol>
 *
 * <p>写操作与 {@link RedisAtRepository} 一样全走 {@code eval(String, List, List)}——这是跨 jedis 3.8→6.0
 * 唯一签名稳定的写通道。
 */
public final class RedisCleanup {
    private final JedisPool pool;
    private final String prefix;
    private final long ttlSeconds;

    public RedisCleanup(JedisPool pool) {
        this(pool, "easy-at");
    }

    public RedisCleanup(JedisPool pool, String prefix) {
        this(pool, prefix, TimeUnit.DAYS.toSeconds(30));
    }

    public RedisCleanup(JedisPool pool, String prefix, long ttlSeconds) {
        this.pool = pool;
        this.prefix = prefix == null ? "easy-at" : prefix;
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : 0L;
    }

    String indexKey() {
        return prefix + ":cleanup:index";
    }

    /**
     * 扫描并删除一批终态事务。
     *
     * <p>不满足删除条件的候选会被重新打上当前时间戳的 score——它们会排到队列尾部， 若长期静止（ACTIVE 卡住、等待人工介入）又会自然老化回队首重新参与扫描。
     * 这样既避免了"未到期候选占满批次导致清理停滞"，又不会永久漏掉任何事务。
     */
    private static final String PURGE_LUA =
            "local idx=KEYS[1]; local pfx=ARGV[1]; local now=tonumber(ARGV[2]);"
                    + "local committedBefore=tonumber(ARGV[3]); local rolledBackBefore=tonumber(ARGV[4]);"
                    + "local batch=tonumber(ARGV[5]); local scanBefore=tonumber(ARGV[6]);"
                    + "local ttl=tonumber(ARGV[7]);"
                    + "local cands=redis.call('ZRANGEBYSCORE', idx, '-inf', scanBefore, 'LIMIT', 0, batch);"
                    + "local out={};"
                    + "for i=1,#cands do"
                    + "  local xid=cands[i];"
                    + "  local g=pfx..':global:'..xid;"
                    + "  local st=redis.call('HGET', g, 'status');"
                    + "  local upd=tonumber(redis.call('HGET', g, 'updated_at')) or 0;"
                    + "  local del=0;"
                    + "  if st=='COMMITTED' and committedBefore>0 and upd<committedBefore then del=1; end;"
                    + "  if st=='ROLLED_BACK' and rolledBackBefore>0 and upd<rolledBackBefore then del=1; end;"
                    + "  if del==1 then"
                    + "    if st then redis.call('SREM', pfx..':global:status:'..st, xid); end;"
                    + "    redis.call('DEL', g);"
                    + "    local undoSet=pfx..':undo:'..xid;"
                    + "    local members=redis.call('SMEMBERS', undoSet);"
                    + "    for j=1,#members do redis.call('DEL', pfx..':undo:'..xid..':'..members[j]); end;"
                    + "    redis.call('DEL', undoSet);"
                    + "    local branchSet=pfx..':branch:byXid:'..xid;"
                    + "    local bids=redis.call('SMEMBERS', branchSet);"
                    + "    for j=1,#bids do"
                    + "      local bk=pfx..':branch:'..bids[j];"
                    + "      local bst=redis.call('HGET', bk, 'status');"
                    + "      local rid=redis.call('HGET', bk, 'resource_id');"
                    + "      if bst then redis.call('SREM', pfx..':branch:status:'..bst, bids[j]); end;"
                    + "      if rid then redis.call('DEL', pfx..':branch:uniq:'..xid..':'..rid); end;"
                    + "      redis.call('DEL', bk);"
                    + "    end;"
                    + "    redis.call('DEL', branchSet);"
                    + "    local lockSet=pfx..':lock:byXid:'..xid;"
                    + "    local locks=redis.call('SMEMBERS', lockSet);"
                    + "    for j=1,#locks do"
                    + "      if redis.call('HGET', locks[j], 'xid')==xid then redis.call('DEL', locks[j]); end;"
                    + "    end;"
                    + "    redis.call('DEL', lockSet);"
                    + "    redis.call('ZREM', idx, xid);"
                    + "    out[#out+1]=xid;"
                    + "  else"
                    + "    redis.call('ZADD', idx, now, xid);"
                    + "  end;"
                    + "end;"
                    + "return out;";

    /** 给某个事务的键续命（把它推进清理索引时一并刷新 TTL）。 */
    private static final String TOUCH_LUA =
            "local pfx=ARGV[1]; local xid=ARGV[2]; local ttl=tonumber(ARGV[3]);"
                    + "if ttl<=0 then return 0; end;"
                    + "local g=pfx..':global:'..xid;"
                    + "if redis.call('EXISTS', g)==1 then redis.call('EXPIRE', g, ttl); end;"
                    + "local undoSet=pfx..':undo:'..xid;"
                    + "redis.call('EXPIRE', undoSet, ttl);"
                    + "local members=redis.call('SMEMBERS', undoSet);"
                    + "for i=1,#members do redis.call('EXPIRE', pfx..':undo:'..xid..':'..members[i], ttl); end;"
                    + "redis.call('ZADD', KEYS[1], ARGV[4], xid);"
                    + "return 1;";

    /**
     * 清理一批终态事务。
     *
     * @param committedBefore COMMITTED 事务的 updated_at 早于该时间戳才可清理（0 表示不清理）
     * @param rolledBackBefore ROLLED_BACK 事务的 updated_at 早于该时间戳才可清理（0 表示不清理）
     * @return 被删除的 xid 列表（混合存储模式下用它级联删除业务库里的 {@code easy_at_undo_log}）
     */
    @SuppressWarnings("unchecked")
    public CleanupResult cleanup(
            long nowMillis, long committedBefore, long rolledBackBefore, int batchSize) {
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
        long scanBefore =
                Math.min(nonZero(committedBefore, nowMillis), nonZero(rolledBackBefore, nowMillis));
        try (Jedis j = pool.getResource()) {
            Object res =
                    j.eval(
                            PURGE_LUA,
                            java.util.Collections.singletonList(indexKey()),
                            java.util.Arrays.asList(
                                    prefix,
                                    String.valueOf(nowMillis),
                                    String.valueOf(committedBefore),
                                    String.valueOf(rolledBackBefore),
                                    String.valueOf(batchSize),
                                    String.valueOf(scanBefore),
                                    String.valueOf(ttlSeconds)));
            List<String> removed = new ArrayList<String>();
            if (res instanceof List)
                for (Object o : (List<Object>) res) removed.add(String.valueOf(o));
            return new CleanupResult(removed);
        } catch (Exception e) {
            throw new AtException("Cannot clean easyAt Redis history", e);
        }
    }

    /** 保活：把某个事务的键 TTL 与清理索引一并刷新。长期有人在推进的事务不会被回收。 */
    public void touch(String xid, long nowMillis) {
        if (ttlSeconds <= 0) return;
        try (Jedis j = pool.getResource()) {
            j.eval(
                    TOUCH_LUA,
                    java.util.Collections.singletonList(indexKey()),
                    java.util.Arrays.asList(
                            prefix, xid, String.valueOf(ttlSeconds), String.valueOf(nowMillis)));
        } catch (Exception e) {
            throw new AtException("Cannot refresh TTL for " + xid, e);
        }
    }

    private static long nonZero(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    public static final class CleanupResult {
        private final List<String> removedXids;

        CleanupResult(List<String> removedXids) {
            this.removedXids = removedXids;
        }

        /** 被删除的事务 xid。混合存储模式下调用方据此级联清理业务库的 undo 表。 */
        public List<String> getRemovedXids() {
            return removedXids;
        }

        public int getRemovedCount() {
            return removedXids.size();
        }
    }
}
