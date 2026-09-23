package io.github.easyat.storage.redis;

import io.github.easyat.core.*;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis global lock. The lock row is a hash holding the owning xid and a lease timestamp. Acquisition
 * is atomic via Lua; takeover of an expired lock only happens after the original transaction has
 * converged (committed/rolled-back/manual/dirty-write). A background thread renews held leases.
 */
public final class RedisGlobalLockManager implements GlobalLockManager, AutoCloseable {
    private final JedisPool pool; private final long leaseMillis; private final long waitMillis; private final String prefix;
    private final ScheduledExecutorService renewer; private final Map<String,String> held=new ConcurrentHashMap<String,String>(); private final AtomicBoolean closed=new AtomicBoolean(false);
    public RedisGlobalLockManager(JedisPool pool){this(pool,30000L,0L,"easy-at");}
    public RedisGlobalLockManager(JedisPool pool,long leaseMillis,long waitMillis){this(pool,leaseMillis,waitMillis,"easy-at");}
    public RedisGlobalLockManager(JedisPool pool,long leaseMillis,long waitMillis,String prefix){
        this.pool=pool;this.leaseMillis=leaseMillis;this.waitMillis=waitMillis;this.prefix=prefix==null?"easy-at":prefix;
        this.renewer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"easy-at-redis-lock-renew");t.setDaemon(true);return t;});
        long period=Math.max(1000L,leaseMillis/3);
        this.renewer.scheduleWithFixedDelay(this::renewAll,period,period,TimeUnit.MILLISECONDS);
    }
    private String key(String r,String t,String k){return prefix+":lock:"+r+":"+t+":"+k;}
    private static final String ACQUIRE_LUA =
        "local key=KEYS[1]; local xid=ARGV[1]; local leaseUntil=ARGV[2]; local now=ARGV[3]; local pfx=ARGV[4];" +
        "if redis.call('EXISTS',key)==0 then redis.call('HSET',key,'xid',xid,'lease_until',leaseUntil); redis.call('SADD',pfx..':lock:byXid:'..xid,key); return 1; end;" +
        "local cur=redis.call('HGET',key,'xid'); local lease=tonumber(redis.call('HGET',key,'lease_until'));" +
        "if cur==xid then redis.call('HSET',key,'lease_until',leaseUntil); return 1; end;" +
        "if lease<now then local status=redis.call('HGET',pfx..':global:'..cur,'status');" +
        " if status==false or status=='COMMITTED' or status=='ROLLED_BACK' or status=='MANUAL_INTERVENTION' or status=='DIRTY_WRITE' then" +
        "  redis.call('DEL',key); redis.call('HSET',key,'xid',xid,'lease_until',leaseUntil); redis.call('SADD',pfx..':lock:byXid:'..xid,key); return 1; end; return 0; end; return 0;";
    private static final String RELEASE_LUA =
        "local set=KEYS[1]; local xid=ARGV[1]; local members=redis.call('SMEMBERS',set);" +
        "for i=1,#members do local k=members[i]; if redis.call('HGET',k,'xid')==xid then redis.call('DEL',k); end; end; redis.call('DEL',set); return 1;";

    @Override public void acquire(String resource,String table,String key,String xid){acquire(resource,table,key,xid,waitMillis);}
    @Override public void acquire(String resource,String table,String pk,String xid,long waitMillis){
        String k=key(resource,table,pk); long deadline=waitMillis<=0?0:System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(waitMillis);
        while(!closed.get()){
            if(tryAcquire(k,xid))return;
            if(waitMillis>0&&System.nanoTime()<deadline){try{Thread.sleep(50);}catch(InterruptedException ie){Thread.currentThread().interrupt();throw new AtException("Interrupted acquiring global lock",ie);}continue;}
            throw new GlobalLockConflictException("Global lock conflict: "+resource+"/"+table+"/"+pk+" held by another transaction");
        }
        throw new AtException("Lock manager is closed");
    }
    private boolean tryAcquire(String k,String xid){
        try(Jedis j=pool.getResource()){
            Object res=j.eval(ACQUIRE_LUA,1,k,xid,String.valueOf(System.currentTimeMillis()+leaseMillis),String.valueOf(System.currentTimeMillis()),prefix);
            if("1".equals(String.valueOf(res))){held.put(k,xid);return true;}
            return false;
        }
    }
    @Override public void releaseByXid(String xid){
        held.entrySet().removeIf(e->e.getValue().equals(xid));
        try(Jedis j=pool.getResource()){j.eval(RELEASE_LUA,1,prefix+":lock:byXid:"+xid,xid);}catch(Exception ignored){}
    }
    private void renewAll(){
        if(closed.get()||held.isEmpty())return;
        long now=System.currentTimeMillis();
        for(Map.Entry<String,String> e:held.entrySet()){
            String k=e.getKey(); String myXid=e.getValue();
            try(Jedis j=pool.getResource()){
                String curXid=j.hget(k,"xid");
                if(curXid!=null&&curXid.equals(myXid))j.hset(k,"lease_until",String.valueOf(now+leaseMillis));
            }catch(Exception ignored){}
        }
    }
    @Override public void close(){if(closed.compareAndSet(false,true))renewer.shutdownNow();}
}
