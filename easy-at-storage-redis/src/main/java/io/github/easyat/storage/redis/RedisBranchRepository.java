package io.github.easyat.storage.redis;

import io.github.easyat.core.*;
import java.util.*;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Redis-backed branch registry with CAS status transitions and a pending-action scan.
 *
 * <p>与 {@link RedisAtRepository} 同样遵守"只调用跨 jedis 3.8→6.0 签名稳定的 API"这条约束： 所有写操作经 {@code eval(String,
 * List, List)} 执行，读操作只用 {@code hgetAll} / {@code smembers}。 详见 {@link RedisAtRepository} 的类注释。
 */
public final class RedisBranchRepository implements BranchRepository {
    private final JedisPool pool;
    private final String prefix;

    public RedisBranchRepository(JedisPool pool) {
        this(pool, "easy-at");
    }

    public RedisBranchRepository(JedisPool pool, String prefix) {
        this.pool = pool;
        this.prefix = prefix == null ? "easy-at" : prefix;
    }

    private String bkey(String id) {
        return prefix + ":branch:" + id;
    }

    private String byXidKey(String xid) {
        return prefix + ":branch:byXid:" + xid;
    }

    private String statusSet(String status) {
        return prefix + ":branch:status:" + status;
    }

    /**
     * (xid, resource_id) 的唯一索引键，等价于 JDBC 侧的 UNIQUE 约束。并发注册时只有一个调用方能 SETNX 成功，后来者读到已有的 branchId ——
     * 保证同一资源在同一全局事务下只有一个分支。
     */
    private String uniqueKey(String xid, String resourceId) {
        return prefix + ":branch:uniq:" + xid + ":" + resourceId;
    }

    /** 唯一的写入通道：只使用签名稳定的 {@code eval(String, List, List)}。 */
    private static Object eval(Jedis j, String script, String[] keys, String[] args) {
        return j.eval(script, Arrays.asList(keys), Arrays.asList(args));
    }

    /**
     * 注册：先用 SETNX 抢占 (xid, resource_id) 唯一索引。
     *
     * <p>ARGV[12] 是本分支 id。若索引已被别的分支占住，则返回那个已有 id（调用方按"已注册"处理）， 不覆盖。
     */
    private static final String REGISTER_LUA =
            "local existing=redis.call('GET', KEYS[4]);"
                    + "if existing then return existing; end;"
                    + "if redis.call('SETNX', KEYS[4], ARGV[12])==0 then"
                    + "  return redis.call('GET', KEYS[4]); end;"
                    + "redis.call('HSET', KEYS[1], 'branch_id',ARGV[1], 'xid',ARGV[2],"
                    + " 'resource_id',ARGV[3], 'status',ARGV[4], 'service_name',ARGV[5],"
                    + " 'callback_url',ARGV[6], 'sequence',ARGV[7], 'retry_count',ARGV[8],"
                    + " 'created_at',ARGV[9], 'updated_at',ARGV[10], 'next_retry_at',ARGV[11]);"
                    + "redis.call('SADD', KEYS[2], ARGV[1]);"
                    + "redis.call('SADD', KEYS[3], ARGV[1]);"
                    + "return ARGV[12];";

    private static final String UPDATE_STATUS_LUA =
            "if redis.call('EXISTS', KEYS[1])==1 then"
                    + " redis.call('HSET', KEYS[1], 'status',ARGV[1], 'updated_at',ARGV[2]); return 1; end;"
                    + "return 0;";

    private static final String UPDATE_RECOVERY_LUA =
            "redis.call('HSET', KEYS[1], 'retry_count',ARGV[1], 'next_retry_at',ARGV[2],"
                    + " 'updated_at',ARGV[3]); return 1;";

    private static final String TRANSITION_LUA =
            "local key=KEYS[1]; local oldSet=KEYS[2]; local newSet=KEYS[3];"
                    + "local expected=ARGV[1]; local nextStatus=ARGV[2]; local now=ARGV[3];"
                    + "if redis.call('HGET',key,'status')~=expected then return 0 end;"
                    + "redis.call('HSET',key,'status',nextStatus,'updated_at',now);"
                    + "redis.call('SREM',oldSet,key); redis.call('SADD',newSet,key); return 1;";

    @Override
    public void register(AtBranch b) {
        try (Jedis j = pool.getResource()) {
            Object res =
                    eval(
                            j,
                            REGISTER_LUA,
                            new String[] {
                                bkey(b.getBranchId()),
                                byXidKey(b.getXid()),
                                statusSet(b.getStatus().name()),
                                uniqueKey(b.getXid(), b.getResourceId())
                            },
                            new String[] {
                                b.getBranchId(),
                                b.getXid(),
                                b.getResourceId(),
                                b.getStatus().name(),
                                b.getServiceName() == null ? "" : b.getServiceName(),
                                b.getCallbackUrl() == null ? "" : b.getCallbackUrl(),
                                String.valueOf(b.getSequence()),
                                String.valueOf(b.getRetries()),
                                String.valueOf(b.getCreatedAt()),
                                String.valueOf(b.getUpdatedAt()),
                                String.valueOf(b.getNextRetryAt()),
                                b.getBranchId()
                            });
            // 返回值不是本分支 id ⇒ (xid, resource_id) 已被别的分支占住：保持幂等，不覆盖、不重复建索引。
            String owner = res == null ? null : String.valueOf(res);
            if (owner != null
                    && !owner.isEmpty()
                    && !"null".equals(owner)
                    && !owner.equals(b.getBranchId())) {
                return;
            }
        }
    }

    @Override
    public Optional<AtBranch> find(String branchId) {
        try (Jedis j = pool.getResource()) {
            return Optional.ofNullable(map(j.hgetAll(bkey(branchId))));
        }
    }

    private AtBranch map(Map<String, String> m) {
        if (m == null || m.isEmpty()) return null;
        return new AtBranch(
                m.get("branch_id"),
                m.get("xid"),
                m.get("resource_id"),
                m.get("service_name"),
                m.get("callback_url"),
                Integer.parseInt(m.getOrDefault("sequence", "1")),
                BranchStatus.valueOf(m.get("status")),
                Integer.parseInt(m.getOrDefault("retry_count", "0")),
                Long.parseLong(m.getOrDefault("created_at", "0")),
                Long.parseLong(m.getOrDefault("updated_at", "0")),
                Long.parseLong(m.getOrDefault("next_retry_at", "0")));
    }

    @Override
    public boolean transition(String branchId, BranchStatus expected, BranchStatus next) {
        try (Jedis j = pool.getResource()) {
            Object res =
                    eval(
                            j,
                            TRANSITION_LUA,
                            new String[] {
                                bkey(branchId), statusSet(expected.name()), statusSet(next.name())
                            },
                            new String[] {
                                expected.name(),
                                next.name(),
                                String.valueOf(System.currentTimeMillis())
                            });
            return "1".equals(String.valueOf(res));
        }
    }

    @Override
    public List<AtBranch> byXid(String xid) {
        List<AtBranch> out = new ArrayList<AtBranch>();
        try (Jedis j = pool.getResource()) {
            for (String id : j.smembers(byXidKey(xid))) {
                AtBranch b = map(j.hgetAll(bkey(id)));
                if (b != null) out.add(b);
            }
        }
        out.sort(
                new Comparator<AtBranch>() {
                    public int compare(AtBranch a, AtBranch b) {
                        return Integer.compare(a.getSequence(), b.getSequence());
                    }
                });
        return out;
    }

    @Override
    public List<AtBranch> pendingActions(long now, int limit) {
        Set<String> ids = new LinkedHashSet<String>();
        try (Jedis j = pool.getResource()) {
            ids.addAll(j.smembers(statusSet(BranchStatus.ROLLING_BACK.name())));
            ids.addAll(j.smembers(statusSet(BranchStatus.ROLLBACK_FAILED.name())));
        }
        List<AtBranch> out = new ArrayList<AtBranch>();
        for (String id : ids) {
            if (out.size() >= limit) break;
            AtBranch b = find(id).orElse(null);
            if (b == null) continue;
            if (b.getNextRetryAt() <= now) out.add(b);
        }
        return out;
    }

    @Override
    public void updateRecovery(String branchId, int retries, long nextRetryAt) {
        try (Jedis j = pool.getResource()) {
            eval(
                    j,
                    UPDATE_RECOVERY_LUA,
                    new String[] {bkey(branchId)},
                    new String[] {
                        String.valueOf(retries),
                        String.valueOf(nextRetryAt),
                        String.valueOf(System.currentTimeMillis())
                    });
        }
    }

    @Override
    public void save(AtBranch b) {
        try (Jedis j = pool.getResource()) {
            Object res =
                    eval(
                            j,
                            UPDATE_STATUS_LUA,
                            new String[] {bkey(b.getBranchId())},
                            new String[] {
                                b.getStatus().name(), String.valueOf(System.currentTimeMillis())
                            });
            if (!"1".equals(String.valueOf(res))) register(b);
        }
    }

    @Override
    public Optional<AtBranch> findByXidResource(String xid, String resourceId) {
        try (Jedis j = pool.getResource()) {
            // 优先走 (xid, resource_id) 唯一索引，命中不了再退回全量扫描（兼容约束上线前的数据）。
            String id = j.get(uniqueKey(xid, resourceId));
            if (id != null && !id.isEmpty()) {
                AtBranch b = map(j.hgetAll(bkey(id)));
                if (b != null) return Optional.of(b);
            }
        }
        for (AtBranch b : byXid(xid)) {
            if (b.getResourceId().equals(resourceId)) return Optional.of(b);
        }
        return Optional.empty();
    }
}
