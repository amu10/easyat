package io.github.easyat.storage.redis;

import io.github.easyat.core.*;
import io.github.easyat.jdbc.JacksonUndoDataCodec;
import java.util.*;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Redis-backed global transaction store. Status changes use a Lua compare-and-set on status+version
 * so multiple recovery instances cannot advance the same transaction concurrently. Recovery
 * ownership and the recovery scan use per-status sets.
 */
public final class RedisAtRepository implements AtRepository {
    private final JedisPool pool;
    private final UndoDataCodec codec;
    private final String prefix;

    public RedisAtRepository(JedisPool pool) {
        this(pool, new JacksonUndoDataCodec(), "easy-at");
    }

    public RedisAtRepository(JedisPool pool, UndoDataCodec codec, String prefix) {
        this.pool = pool;
        this.codec = codec;
        this.prefix = prefix == null ? "easy-at" : prefix;
    }

    private String gkey(String xid) {
        return prefix + ":global:" + xid;
    }

    private String statusSet(String status) {
        return prefix + ":global:status:" + status;
    }

    private String undoKey(String xid) {
        return prefix + ":undo:" + xid;
    }

    private String undoIdKey(String xid, String undoId) {
        return prefix + ":undo:" + xid + ":" + undoId;
    }

    private static final String TRANSITION_LUA =
            "local key=KEYS[1]; local oldSet=KEYS[2]; local newSet=KEYS[3];"
                    + "local xid=ARGV[1]; local expected=ARGV[2]; local expectedVer=ARGV[3]; local nextStatus=ARGV[4]; local now=ARGV[5];"
                    + "if redis.call('HGET',key,'status')~=expected then return 0 end;"
                    + "if tonumber(redis.call('HGET',key,'version'))~=tonumber(expectedVer) then return 0 end;"
                    + "redis.call('HSET',key,'status',nextStatus,'version',tonumber(redis.call('HGET',key,'version'))+1,'updated_at',now);"
                    + "redis.call('SREM',oldSet,xid); redis.call('SADD',newSet,xid); return 1;";
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
            Map<String, String> m = new HashMap<String, String>();
            m.put("name", tx.getName());
            m.put("status", tx.getStatus().name());
            m.put("timeout_at", String.valueOf(tx.getDeadline()));
            m.put("retry_count", "0");
            m.put("next_retry_at", "0");
            m.put("version", "0");
            m.put("owner", "");
            m.put("lease_until", "0");
            long now = System.currentTimeMillis();
            m.put("created_at", String.valueOf(now));
            m.put("updated_at", String.valueOf(now));
            j.hset(gkey(tx.getXid()), m);
            j.sadd(statusSet(tx.getStatus().name()), tx.getXid());
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
            j.del(undoKey(tx.getXid()));
            for (UndoRecord u : tx.getUndoRecords()) {
                Map<String, String> m = new HashMap<String, String>();
                UndoContext ctx = new UndoContext(u.getResourceId(), u.getTableName());
                m.put("resource_id", u.getResourceId());
                m.put("table_name", u.getTableName());
                m.put("pk_name", u.getPrimaryKeyColumn());
                m.put("pk_value", String.valueOf(u.getPrimaryKeyValue()));
                m.put("rollback_sql", u.getRollbackSql());
                m.put("rollback_params", b64(codec.encodeParameters(u.getParameters(), ctx)));
                if (u.getBeforeImage() != null)
                    m.put("before_image", b64(codec.encodeRowImage(u.getBeforeImage(), ctx)));
                if (u.getAfterImage() != null)
                    m.put("after_image", b64(codec.encodeRowImage(u.getAfterImage(), ctx)));
                m.put("status", u.isRolledBack() ? "ROLLED_BACK" : "EXECUTED");
                j.hset(undoIdKey(tx.getXid(), u.getId()), m);
                j.sadd(undoKey(tx.getXid()), u.getId());
            }
        }
    }

    @Override
    public boolean transition(String xid, AtStatus expected, long expectedVersion, AtStatus next) {
        try (Jedis j = pool.getResource()) {
            Object res =
                    j.eval(
                            TRANSITION_LUA,
                            3,
                            gkey(xid),
                            statusSet(expected.name()),
                            statusSet(next.name()),
                            xid,
                            expected.name(),
                            String.valueOf(expectedVersion),
                            next.name(),
                            String.valueOf(System.currentTimeMillis()));
            return "1".equals(String.valueOf(res));
        }
    }

    @Override
    public boolean claimLease(String xid, String owner, long leaseUntil, long now) {
        try (Jedis j = pool.getResource()) {
            Object res =
                    j.eval(
                            CLAIM_LUA,
                            1,
                            gkey(xid),
                            owner,
                            String.valueOf(leaseUntil),
                            String.valueOf(now));
            return "1".equals(String.valueOf(res));
        }
    }

    @Override
    public void releaseLease(String xid, String owner) {
        try (Jedis j = pool.getResource()) {
            j.eval(RELEASE_LUA, 1, gkey(xid), owner);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void updateRecovery(String xid, int retries, long nextRetryAt) {
        try (Jedis j = pool.getResource()) {
            Map<String, String> m = new HashMap<String, String>();
            m.put("retry_count", String.valueOf(retries));
            m.put("next_retry_at", String.valueOf(nextRetryAt));
            m.put("updated_at", String.valueOf(System.currentTimeMillis()));
            j.hset(gkey(xid), m);
        }
    }

    @Override
    public List<AtTransaction> recoverable(long now, int limit) {
        List<AtTransaction> out = new ArrayList<AtTransaction>();
        Set<String> candidates = new LinkedHashSet<String>();
        try (Jedis j = pool.getResource()) {
            candidates.addAll(j.smembers(statusSet(AtStatus.ROLLING_BACK.name())));
            candidates.addAll(j.smembers(statusSet(AtStatus.ROLLBACK_FAILED.name())));
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
