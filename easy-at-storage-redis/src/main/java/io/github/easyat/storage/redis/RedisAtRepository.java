package io.github.easyat.storage.redis;

import io.github.easyat.core.*;
import io.github.easyat.jdbc.JacksonUndoDataCodec;
import java.util.*;
import java.util.concurrent.TimeUnit;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Redis-backed global transaction store. Status changes use a Lua compare-and-set on status+version
 * so multiple recovery instances cannot advance the same transaction concurrently. Recovery
 * ownership and the recovery scan use per-status sets.
 *
 * <h2>为什么所有写操作都走 Lua</h2>
 *
 * <p>jedis 的<strong>写</strong>命令在版本间改过返回值类型：{@code hset/sadd/del/exists} 在 3.x 返回 {@code
 * Long}/{@code Boolean}，从 4.x 起改成了基本类型 {@code long}/{@code boolean}。 JVM 的方法描述符包含返回类型，所以"按 3.8
 * 编译、跑在 4/5/6 上"会抛 {@code NoSuchMethodError}——反之亦然。
 *
 * <p>而 Spring Boot 2.7 的 BOM 管理 jedis <b>3.8.0</b>、Spring Boot 3.5 的 BOM 管理 jedis <b>6.0.0</b>；使用者的
 * dependencyManagement 会覆盖本模块声明的版本，我们无法控制运行期是哪一个。
 *
 * <p>因此本类只调用<b>跨 3.8→6.0 签名完全一致</b>的方法：
 *
 * <ul>
 *   <li>写：一律 {@code eval(String, List<String>, List<String>)}（Lua 脚本内执行 HSET/SADD/DEL）
 *   <li>读：{@code hget} / {@code hgetAll} / {@code smembers}（返回类型从未变过）
 * </ul>
 *
 * 这样无论按哪个版本编译、运行期是哪一版，字节码都能解析。
 */
public final class RedisAtRepository implements AtRepository {
    /** Redis 连接池（所有写操作经它执行 Lua，读操作走 hgetAll/smembers）。 */
    private final JedisPool pool;

    /** before/after image 与参数的编解码器。 */
    private final UndoDataCodec codec;

    /** 所有键的统一前缀（默认 "easy-at"），多租户/多实例隔离用。 */
    private final String prefix;

    /**
     * 兜底 TTL（秒）。每个键在每次被写入时刷新一次，因此"有人在推进的事务"永远不会过期； 只有彻底没人管、且清理器也挂掉的孤儿键才会最终被 Redis 回收。0 表示不设 TTL。
     */
    private final long ttlSeconds;

    /** 配套的终止态清理器，由 starter 装配到定时调度。 */
    private final RedisCleanup cleanup;

    public RedisAtRepository(JedisPool pool) {
        this(pool, new JacksonUndoDataCodec(), "easy-at");
    }

    public RedisAtRepository(JedisPool pool, UndoDataCodec codec, String prefix) {
        this(pool, codec, prefix, TimeUnit.DAYS.toSeconds(30));
    }

    public RedisAtRepository(JedisPool pool, UndoDataCodec codec, String prefix, long ttlSeconds) {
        this.pool = pool;
        this.codec = codec;
        this.prefix = prefix == null ? "easy-at" : prefix;
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : 0L;
        this.cleanup = new RedisCleanup(pool, this.prefix, this.ttlSeconds);
    }

    /** 该存储配套的清理器。starter 装配到定时调度上。 */
    public RedisCleanup cleanup() {
        return cleanup;
    }

    protected String prefix() {
        return prefix;
    }

    private String gkey(String xid) {
        return prefix + ":global:" + xid;
    }

    private String statusSet(String status) {
        return prefix + ":global:status:" + status;
    }

    /** 清理索引：member = xid，score = 最后更新时间。清理器靠它按时间批量捞过期候选。 */
    String cleanupIndex() {
        return prefix + ":cleanup:index";
    }

    private String undoKey(String xid) {
        return prefix + ":undo:" + xid;
    }

    private String undoIdKey(String xid, String undoId) {
        return prefix + ":undo:" + xid + ":" + undoId;
    }

    /** 唯一的写入通道：只使用签名稳定的 {@code eval(String, List, List)}。 */
    private static Object eval(Jedis j, String script, String[] keys, String[] args) {
        return j.eval(script, Arrays.asList(keys), Arrays.asList(args));
    }

    private static final String CREATE_LUA =
            "redis.call('HSET', KEYS[1],"
                    + " 'name',ARGV[1], 'status',ARGV[2], 'timeout_at',ARGV[3], 'retry_count',ARGV[4],"
                    + " 'next_retry_at',ARGV[5], 'version',ARGV[6], 'owner',ARGV[7], 'lease_until',ARGV[8],"
                    + " 'created_at',ARGV[9], 'updated_at',ARGV[10]);"
                    + "redis.call('SADD', KEYS[2], ARGV[11]);"
                    + "redis.call('ZADD', KEYS[3], ARGV[10], ARGV[11]);"
                    + "if tonumber(ARGV[12])>0 then redis.call('EXPIRE', KEYS[1], ARGV[12]); end;"
                    + "return 1;";

    /**
     * 清空该事务的 undo：索引集合<b>和它引用的所有 undo 实体</b>都要删。
     *
     * <p>旧实现只 DEL 索引集合，实体 hash 从此再无人引用——既占内存，又连扫描都扫不到。 save() 是整体重写，所以这里必须连实体一起删干净。
     */
    private static final String CLEAR_UNDO_LUA =
            "local set=KEYS[1]; local pfx=ARGV[1]; local xid=ARGV[2];"
                    + "local members=redis.call('SMEMBERS', set);"
                    + "for i=1,#members do redis.call('DEL', pfx..':undo:'..xid..':'..members[i]); end;"
                    + "redis.call('DEL', set); return #members;";

    private static final String WRITE_UNDO_LUA =
            "redis.call('HSET', KEYS[1],"
                    + " 'resource_id',ARGV[1], 'table_name',ARGV[2], 'pk_name',ARGV[3], 'pk_value',ARGV[4],"
                    + " 'rollback_sql',ARGV[5], 'rollback_params',ARGV[6], 'status',ARGV[7]);"
                    + "if ARGV[8] ~= '' then redis.call('HSET', KEYS[1], 'before_image', ARGV[8]); end;"
                    + "if ARGV[9] ~= '' then redis.call('HSET', KEYS[1], 'after_image', ARGV[9]); end;"
                    + "redis.call('SADD', KEYS[2], ARGV[10]);"
                    + "if tonumber(ARGV[11])>0 then redis.call('EXPIRE', KEYS[1], ARGV[11]); redis.call('EXPIRE', KEYS[2], ARGV[11]); end;"
                    + "return 1;";

    private static final String UPDATE_RECOVERY_LUA =
            "redis.call('HSET', KEYS[1], 'retry_count',ARGV[1], 'next_retry_at',ARGV[2],"
                    + " 'updated_at',ARGV[3]); redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4]);"
                    + "if tonumber(ARGV[5])>0 then redis.call('EXPIRE', KEYS[1], ARGV[5]); end;"
                    + "return 1;";

    private static final String TRANSITION_LUA =
            "local key=KEYS[1]; local oldSet=KEYS[2]; local newSet=KEYS[3]; local idx=KEYS[4];"
                    + "local xid=ARGV[1]; local expected=ARGV[2]; local expectedVer=ARGV[3]; local nextStatus=ARGV[4]; local now=ARGV[5];"
                    + "if redis.call('HGET',key,'status')~=expected then return 0 end;"
                    + "if tonumber(redis.call('HGET',key,'version'))~=tonumber(expectedVer) then return 0 end;"
                    + "redis.call('HSET',key,'status',nextStatus,'version',tonumber(redis.call('HGET',key,'version'))+1,'updated_at',now);"
                    + "redis.call('SREM',oldSet,xid); redis.call('SADD',newSet,xid);"
                    + "redis.call('ZADD',idx,now,xid);"
                    + "if tonumber(ARGV[6])>0 then redis.call('EXPIRE',key,ARGV[6]); end;"
                    + "return 1;";
    private static final String CLAIM_LUA =
            "local key=KEYS[1]; local owner=ARGV[1]; local leaseUntil=ARGV[2]; local now=ARGV[3];"
                    + "local status=redis.call('HGET',key,'status');"
                    + "if status=='COMMITTED' or status=='ROLLED_BACK' or status=='MANUAL_INTERVENTION' or status=='DIRTY_WRITE' then return 0 end;"
                    + "local cur=redis.call('HGET',key,'owner'); local curLease=redis.call('HGET',key,'lease_until');"
                    + "if cur==false or cur=='' or cur==owner or tonumber(curLease)<tonumber(now) then"
                    + "  redis.call('HSET',key,'owner',owner,'lease_until',leaseUntil,'updated_at',now); return 1; end;"
                    + "return 0;";
    private static final String RELEASE_LUA =
            "local key=KEYS[1]; local owner=ARGV[1]; local cur=redis.call('HGET',key,'owner');"
                    + "if cur==owner or cur==false or cur=='' then redis.call('HSET',key,'owner','','lease_until','0'); return 1; end; return 0;";

    @Override
    public void create(AtTransaction tx) {
        try (Jedis j = pool.getResource()) {
            long now = System.currentTimeMillis();
            eval(
                    j,
                    CREATE_LUA,
                    new String[] {
                        gkey(tx.getXid()), statusSet(tx.getStatus().name()), cleanupIndex()
                    },
                    new String[] {
                        tx.getName(),
                        tx.getStatus().name(),
                        String.valueOf(tx.getDeadline()),
                        "0",
                        "0",
                        "0",
                        "",
                        "0",
                        String.valueOf(now),
                        String.valueOf(now),
                        tx.getXid(),
                        String.valueOf(ttlSeconds)
                    });
        }
    }

    @Override
    public Optional<AtTransaction> find(String xid) {
        try (Jedis j = pool.getResource()) {
            Map<String, String> m = j.hgetAll(gkey(xid));
            if (m.isEmpty()) return Optional.empty();
            long created = Long.parseLong(m.getOrDefault("created_at", "0"));
            AtTransaction tx =
                    new AtTransaction(
                            xid,
                            m.get("name"),
                            created,
                            Long.parseLong(m.getOrDefault("timeout_at", "0")));
            long next = Long.parseLong(m.getOrDefault("next_retry_at", "0"));
            tx.restore(
                    AtStatus.valueOf(m.get("status")),
                    Integer.parseInt(m.getOrDefault("retry_count", "0")),
                    next);
            tx.setVersion(Long.parseLong(m.getOrDefault("version", "0")));
            String owner = m.get("owner");
            if (owner != null && !owner.isEmpty()) tx.setOwner(owner);
            long lease = Long.parseLong(m.getOrDefault("lease_until", "0"));
            if (lease > 0) tx.setLeaseUntil(lease);
            loadUndo(j, tx);
            return Optional.of(tx);
        }
    }

    private void loadUndo(Jedis j, AtTransaction tx) {
        for (String id : j.smembers(undoKey(tx.getXid()))) {
            Map<String, String> m = j.hgetAll(undoIdKey(tx.getXid(), id));
            if (m.isEmpty()) continue;
            UndoContext ctx = new UndoContext(m.get("resource_id"), m.get("table_name"));
            Object[] params =
                    m.containsKey("rollback_params")
                            ? codec.decodeParameters(b64(m.get("rollback_params")), ctx)
                            : new Object[0];
            UndoRecord u =
                    new UndoRecord(
                            id,
                            tx.getXid(),
                            m.get("resource_id"),
                            m.get("table_name"),
                            m.get("pk_name"),
                            m.get("pk_value"),
                            m.get("rollback_sql"),
                            params);
            if (m.containsKey("before_image"))
                u.setBeforeImage(codec.decodeRowImage(b64(m.get("before_image")), ctx));
            if (m.containsKey("after_image"))
                u.setAfterImage(codec.decodeRowImage(b64(m.get("after_image")), ctx));
            if ("ROLLED_BACK".equals(m.get("status"))) u.markRolledBack();
            tx.addUndo(u);
        }
    }

    @Override
    public void save(AtTransaction tx) {
        try (Jedis j = pool.getResource()) {
            eval(
                    j,
                    CLEAR_UNDO_LUA,
                    new String[] {undoKey(tx.getXid())},
                    new String[] {prefix, tx.getXid()});
            for (UndoRecord u : tx.getUndoRecords()) {
                UndoContext ctx = new UndoContext(u.getResourceId(), u.getTableName());
                String before =
                        u.getBeforeImage() == null
                                ? ""
                                : b64(codec.encodeRowImage(u.getBeforeImage(), ctx));
                String after =
                        u.getAfterImage() == null
                                ? ""
                                : b64(codec.encodeRowImage(u.getAfterImage(), ctx));
                eval(
                        j,
                        WRITE_UNDO_LUA,
                        new String[] {undoIdKey(tx.getXid(), u.getId()), undoKey(tx.getXid())},
                        new String[] {
                            u.getResourceId(),
                            u.getTableName(),
                            u.getPrimaryKeyColumn(),
                            String.valueOf(u.getPrimaryKeyValue()),
                            u.getRollbackSql(),
                            b64(codec.encodeParameters(u.getParameters(), ctx)),
                            u.isRolledBack() ? "ROLLED_BACK" : "EXECUTED",
                            before,
                            after,
                            u.getId(),
                            String.valueOf(ttlSeconds)
                        });
            }
        }
    }

    @Override
    public boolean transition(String xid, AtStatus expected, long expectedVersion, AtStatus next) {
        try (Jedis j = pool.getResource()) {
            Object res =
                    eval(
                            j,
                            TRANSITION_LUA,
                            new String[] {
                                gkey(xid),
                                statusSet(expected.name()),
                                statusSet(next.name()),
                                cleanupIndex()
                            },
                            new String[] {
                                xid,
                                expected.name(),
                                String.valueOf(expectedVersion),
                                next.name(),
                                String.valueOf(System.currentTimeMillis()),
                                String.valueOf(ttlSeconds)
                            });
            return "1".equals(String.valueOf(res));
        }
    }

    @Override
    public boolean claimLease(String xid, String owner, long leaseUntil, long now) {
        try (Jedis j = pool.getResource()) {
            Object res =
                    eval(
                            j,
                            CLAIM_LUA,
                            new String[] {gkey(xid)},
                            new String[] {owner, String.valueOf(leaseUntil), String.valueOf(now)});
            return "1".equals(String.valueOf(res));
        }
    }

    @Override
    public void releaseLease(String xid, String owner) {
        try (Jedis j = pool.getResource()) {
            eval(j, RELEASE_LUA, new String[] {gkey(xid)}, new String[] {owner});
        } catch (Exception ignored) {
        }
    }

    @Override
    public void updateRecovery(String xid, int retries, long nextRetryAt) {
        try (Jedis j = pool.getResource()) {
            eval(
                    j,
                    UPDATE_RECOVERY_LUA,
                    new String[] {gkey(xid), cleanupIndex()},
                    new String[] {
                        String.valueOf(retries),
                        String.valueOf(nextRetryAt),
                        String.valueOf(System.currentTimeMillis()),
                        xid,
                        String.valueOf(ttlSeconds)
                    });
        }
    }

    @Override
    public List<AtTransaction> recoverable(long now, int limit) {
        List<AtTransaction> out = new ArrayList<AtTransaction>();
        Set<String> candidates = new LinkedHashSet<String>();
        try (Jedis j = pool.getResource()) {
            candidates.addAll(j.smembers(statusSet(AtStatus.ROLLING_BACK.name())));
            candidates.addAll(j.smembers(statusSet(AtStatus.ROLLBACK_FAILED.name())));
            // COMMITTING：本地已提交但全局推进失败，需继续收敛，否则事务与全局锁永久泄漏
            candidates.addAll(j.smembers(statusSet(AtStatus.COMMITTING.name())));
            for (String xid : j.smembers(statusSet(AtStatus.ACTIVE.name()))) {
                String t = j.hget(gkey(xid), "timeout_at");
                if (t != null && Long.parseLong(t) <= now) candidates.add(xid);
            }
        }
        for (String xid : candidates) {
            if (out.size() >= limit) break;
            Optional<AtTransaction> tx = find(xid);
            if (!tx.isPresent()) continue;
            AtTransaction t = tx.get();
            if (t.getStatus() == AtStatus.ROLLING_BACK) out.add(t);
            else if (t.getStatus() == AtStatus.ROLLBACK_FAILED && t.getNextRetryAt() <= now)
                out.add(t);
            else if (t.getStatus() == AtStatus.ACTIVE && t.getDeadline() <= now) out.add(t);
            else if (t.getStatus() == AtStatus.COMMITTING && t.getNextRetryAt() <= now) out.add(t);
        }
        return out;
    }

    @Override
    public List<AtTransaction> findByStatus(AtStatus status, int limit) {
        List<AtTransaction> out = new ArrayList<AtTransaction>();
        try (Jedis j = pool.getResource()) {
            for (String xid : j.smembers(statusSet(status.name()))) {
                if (out.size() >= limit) break;
                Optional<AtTransaction> tx = find(xid);
                if (tx.isPresent()) out.add(tx.get());
            }
        }
        return out;
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static byte[] b64(String s) {
        return Base64.getDecoder().decode(s);
    }
}
