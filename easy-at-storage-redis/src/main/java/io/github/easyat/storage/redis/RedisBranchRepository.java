package io.github.easyat.storage.redis;

import io.github.easyat.core.*;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import java.util.*;

/** Redis-backed branch registry with CAS status transitions and a pending-action scan. */
public final class RedisBranchRepository implements BranchRepository {
    private final JedisPool pool; private final String prefix;
    public RedisBranchRepository(JedisPool pool){this(pool,"easy-at");}
    public RedisBranchRepository(JedisPool pool,String prefix){this.pool=pool;this.prefix=prefix==null?"easy-at":prefix;}

    private String bkey(String id){return prefix+":branch:"+id;}
    private String byXidKey(String xid){return prefix+":branch:byXid:"+xid;}
    private String statusSet(String status){return prefix+":branch:status:"+status;}

    private static final String TRANSITION_LUA =
        "local key=KEYS[1]; local oldSet=KEYS[2]; local newSet=KEYS[3];" +
        "local expected=ARGV[1]; local nextStatus=ARGV[2]; local now=ARGV[3];" +
        "if redis.call('HGET',key,'status')~=expected then return 0 end;" +
        "redis.call('HSET',key,'status',nextStatus,'updated_at',now);" +
        "redis.call('SREM',oldSet,key); redis.call('SADD',newSet,key); return 1;";

    @Override public void register(AtBranch b){
        try(Jedis j=pool.getResource()){
            write(j,b); j.sadd(byXidKey(b.getXid()),b.getBranchId()); j.sadd(statusSet(b.getStatus().name()),b.getBranchId());
        }
    }
    private void write(Jedis j,AtBranch b){
        Map<String,String> m=new HashMap<String,String>();
        m.put("branch_id",b.getBranchId()); m.put("xid",b.getXid()); m.put("resource_id",b.getResourceId());
        m.put("status",b.getStatus().name()); m.put("service_name",b.getServiceName()==null?"":b.getServiceName());
        m.put("callback_url",b.getCallbackUrl()==null?"":b.getCallbackUrl());
        m.put("sequence",String.valueOf(b.getSequence())); m.put("retry_count",String.valueOf(b.getRetries()));
        m.put("created_at",String.valueOf(b.getCreatedAt())); m.put("updated_at",String.valueOf(b.getUpdatedAt()));
        m.put("next_retry_at",String.valueOf(b.getNextRetryAt()));
        j.hset(bkey(b.getBranchId()),m);
    }
    @Override public Optional<AtBranch> find(String branchId){
        try(Jedis j=pool.getResource()){return Optional.ofNullable(map(j.hgetAll(bkey(branchId))));}
    }
    private AtBranch map(Map<String,String> m){
        if(m==null||m.isEmpty())return null;
        return new AtBranch(m.get("branch_id"),m.get("xid"),m.get("resource_id"),m.get("service_name"),m.get("callback_url"),
            Integer.parseInt(m.getOrDefault("sequence","1")),BranchStatus.valueOf(m.get("status")),
            Integer.parseInt(m.getOrDefault("retry_count","0")),
            Long.parseLong(m.getOrDefault("created_at","0")),Long.parseLong(m.getOrDefault("updated_at","0")),
            Long.parseLong(m.getOrDefault("next_retry_at","0")));
    }
    @Override public boolean transition(String branchId,BranchStatus expected,BranchStatus next){
        try(Jedis j=pool.getResource()){
            Object res=j.eval(TRANSITION_LUA,3,bkey(branchId),statusSet(expected.name()),statusSet(next.name()),expected.name(),next.name(),String.valueOf(System.currentTimeMillis()));
            return "1".equals(String.valueOf(res));
        }
    }
    @Override public List<AtBranch> byXid(String xid){
        List<AtBranch> out=new ArrayList<AtBranch>();
        try(Jedis j=pool.getResource()){for(String id:j.smembers(byXidKey(xid))){AtBranch b=map(j.hgetAll(bkey(id)));if(b!=null)out.add(b);}}
        out.sort(new Comparator<AtBranch>(){public int compare(AtBranch a,AtBranch b){return Integer.compare(a.getSequence(),b.getSequence());}});
        return out;
    }
    @Override public List<AtBranch> pendingActions(long now,int limit){
        Set<String> ids=new LinkedHashSet<String>();
        try(Jedis j=pool.getResource()){ids.addAll(j.smembers(statusSet(BranchStatus.ROLLING_BACK.name())));ids.addAll(j.smembers(statusSet(BranchStatus.ROLLBACK_FAILED.name())));}
        List<AtBranch> out=new ArrayList<AtBranch>();
        for(String id:ids){
            if(out.size()>=limit)break;
            AtBranch b=find(id).orElse(null); if(b==null)continue;
            if(b.getNextRetryAt()<=now)out.add(b);
        }
        return out;
    }
    @Override public void updateRecovery(String branchId,int retries,long nextRetryAt){
        try(Jedis j=pool.getResource()){
            Map<String,String> m=new HashMap<String,String>();
            m.put("retry_count",String.valueOf(retries));
            m.put("next_retry_at",String.valueOf(nextRetryAt));
            m.put("updated_at",String.valueOf(System.currentTimeMillis()));
            j.hset(bkey(branchId),m);
        }
    }
    @Override public void save(AtBranch b){
        try(Jedis j=pool.getResource()){
            if(j.exists(bkey(b.getBranchId()))){
                j.hset(bkey(b.getBranchId()),"status",b.getStatus().name());
                j.hset(bkey(b.getBranchId()),"updated_at",String.valueOf(System.currentTimeMillis()));
            } else register(b);
        }
    }
}
