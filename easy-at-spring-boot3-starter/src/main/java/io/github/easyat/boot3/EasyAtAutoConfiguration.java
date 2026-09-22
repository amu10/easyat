package io.github.easyat.boot3;

import io.github.easyat.core.*;
import io.github.easyat.jdbc.*;
import io.github.easyat.spring.*;
import io.github.easyat.storage.file.*;
import io.github.easyat.storage.redis.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import javax.sql.DataSource;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Wires the embedded AT transaction framework into a Spring Boot 3 application: configuration
 * binding, metrics, dialect-aware undo, local-transaction bridge, multi-instance recovery lease,
 * branch coordination, signed cross-service transport, management/ops API and MDC logging.
 */
@AutoConfiguration
@ConditionalOnClass(EasyAtAspect.class)
@EnableConfigurationProperties(EasyAtProperties.class)
public class EasyAtAutoConfiguration {
    private final EasyAtProperties props; private final String owner;
    public EasyAtAutoConfiguration(EasyAtProperties props){
        this.props=props;
        String h="unknown";
        try{ h=java.net.InetAddress.getLocalHost().getHostName(); }catch(Exception ignored){}
        this.owner=props.getApplicationName()+"@"+h;
    }

    @Bean @ConditionalOnMissingBean HmacSigner easyAtHmacSigner(){return new HmacSigner(props.getTransport().getHmacSecret());}
    @Bean @ConditionalOnMissingBean EasyAtMetrics easyAtMetrics(ObjectProvider<MeterRegistry> registry){return new EasyAtMetrics(registry.getIfAvailable());}

    @Bean @ConditionalOnMissingBean @ConditionalOnProperty(prefix="easy-at.storage",name="type",havingValue="jdbc")
    AtRepository jdbcAtRepository(DataSource dataSource){return new JdbcAtRepository(dataSource);}
    @Bean @ConditionalOnMissingBean @ConditionalOnProperty(prefix="easy-at.storage",name="type",havingValue="file",matchIfMissing=true)
    AtRepository fileAtRepository(){return new FileAtRepository(Paths.get(props.getStorage().getFileDir()));}
    @Bean @ConditionalOnMissingBean @ConditionalOnProperty(prefix="easy-at.storage",name="type",havingValue="redis")
    AtRepository redisAtRepository(JedisPool pool){return new RedisAtRepository(pool);}

    @Bean @ConditionalOnMissingBean @ConditionalOnProperty(prefix="easy-at.lock",name="type",havingValue="jdbc")
    GlobalLockManager jdbcAtLockManager(DataSource dataSource){return new JdbcGlobalLockManager(dataSource,props.getLock().getLease().toMillis(),props.getLock().getWaitTimeout().toMillis());}
    @Bean @ConditionalOnMissingBean @ConditionalOnProperty(prefix="easy-at.lock",name="type",havingValue="file",matchIfMissing=true)
    GlobalLockManager fileAtLockManager(){return new FileGlobalLockManager(Paths.get(props.getStorage().getFileDir()+"-locks"),props.getLock().getWaitTimeout().toMillis());}
    @Bean @ConditionalOnMissingBean @ConditionalOnProperty(prefix="easy-at.lock",name="type",havingValue="redis")
    GlobalLockManager redisAtLockManager(JedisPool pool){return new RedisGlobalLockManager(pool,props.getLock().getLease().toMillis(),props.getLock().getWaitTimeout().toMillis());}

    @Bean @ConditionalOnMissingBean UndoExecutor easyAtUndoExecutor(Map<String,DataSource> sources){return new JdbcUndoExecutor(sources);}
    @Bean @ConditionalOnMissingBean AtTransactionManager easyAtManager(AtRepository r,UndoExecutor u,GlobalLockManager l){return new AtTransactionManager(r,u,l,props.getRecovery().getMaxRetries());}

    @Bean @ConditionalOnMissingBean @ConditionalOnProperty(prefix="easy-at.storage",name="type",havingValue="jdbc")
    BranchRepository jdbcBranchRepository(DataSource dataSource){return new JdbcBranchRepository(dataSource);}
    @Bean @ConditionalOnMissingBean @ConditionalOnProperty(prefix="easy-at.storage",name="type",havingValue="file",matchIfMissing=true)
    BranchRepository fileBranchRepository(){return new FileBranchRepository(Paths.get(props.getStorage().getFileDir()+"-branches"));}
    @Bean @ConditionalOnMissingBean @ConditionalOnProperty(prefix="easy-at.storage",name="type",havingValue="redis")
    BranchRepository redisBranchRepository(JedisPool pool){return new RedisBranchRepository(pool);}

    @Bean @ConditionalOnMissingBean BranchCoordinator branchCoordinator(BranchRepository branches,AtTransactionManager manager,HmacSigner signer,EasyAtMetrics metrics){
        return new BranchCoordinator(branches,manager,signer,props.getApplicationName(),metrics);
    }
    @Bean @ConditionalOnMissingBean EasyAtTransportSecurity easyAtTransportSecurity(HmacSigner signer){return new EasyAtTransportSecurity(signer,props.isProduction());}
    @Bean @ConditionalOnMissingBean CoordinationService coordinationService(BranchCoordinator coordinator,EasyAtTransportSecurity security){return new CoordinationService(coordinator,security);}
    @Bean @ConditionalOnMissingBean ManagementService managementService(AtRepository r,AtTransactionManager m,EasyAtMetrics metrics){
        return new ManagementService(r,m,metrics,props.getManagement().isEnabled(),props.getManagement().getToken());
    }

    @Bean(destroyMethod="close") @ConditionalOnMissingBean @ConditionalOnProperty(prefix="easy-at.recovery",name="enabled",havingValue="true",matchIfMissing=true)
    AtRecoveryScheduler easyAtRecoveryScheduler(AtRepository r,AtTransactionManager m,BranchCoordinator coordinator,EasyAtMetrics metrics){
        AtRecoveryScheduler s=new AtRecoveryScheduler(r,m,coordinator,metrics,owner,props.getRecovery().getInterval().toMillis(),props.getRecovery().getBatchSize(),props.getRecovery().getLease().toMillis());
        s.start();return s;
    }
    @Bean(destroyMethod="close") @ConditionalOnMissingBean @ConditionalOnProperty(prefix="easy-at.recovery",name="enabled",havingValue="true",matchIfMissing=true)
    BranchRetryScheduler branchRetryScheduler(BranchRepository branches,BranchCoordinator coordinator){
        BranchRetryScheduler s=new BranchRetryScheduler(branches,coordinator,props.getRecovery().getInterval().toMillis(),props.getRecovery().getBatchSize());
        s.start();return s;
    }

    @Bean EasyAtAspect easyAtAspect(AtTransactionManager m,BranchCoordinator c,EasyAtMetrics metrics){return new EasyAtAspect(m,c,metrics);}
    @Bean static AtDataSourceBeanPostProcessor easyAtDataSourceBeanPostProcessor(AtTransactionManager m,GlobalLockManager l,ObjectProvider<BranchRepository> branchRepo,EasyAtProperties p){
        BranchRegistrar registrar=new DefaultBranchRegistrar(() -> branchRepo.getIfAvailable(),p.getApplicationName());
        return new AtDataSourceBeanPostProcessor(m,l,registrar,p);
    }

    @Bean @ConditionalOnWebApplication EasyAtCoordinationController easyAtCoordinationController(CoordinationService service){return new EasyAtCoordinationController(service);}
    @Bean @ConditionalOnWebApplication @ConditionalOnProperty(prefix="easy-at.management",name="enabled",havingValue="true")
    EasyAtManagementController easyAtManagementController(ManagementService service){return new EasyAtManagementController(service);}

    @Bean @ConditionalOnClass(Filter.class) FilterRegistrationBean<AtXidFilter> easyAtXidFilter(AtTransactionManager m,EasyAtTransportSecurity security){
        FilterRegistrationBean<AtXidFilter> b=new FilterRegistrationBean<AtXidFilter>(new AtXidFilter(m,security));b.setOrder(-100);return b;
    }
    @Bean @ConditionalOnClass(Filter.class) FilterRegistrationBean<EasyAtMdcFilter> easyAtMdcFilter(){
        FilterRegistrationBean<EasyAtMdcFilter> b=new FilterRegistrationBean<EasyAtMdcFilter>(new EasyAtMdcFilter());b.setOrder(-90);return b;
    }
    @Bean @ConditionalOnClass(name="org.springframework.web.client.RestTemplate") RestTemplateCustomizer easyAtRestTemplateCustomizer(HmacSigner signer){
        return new EasyAtRestTemplateCustomizer(new AtRestTemplateInterceptor(signer,props.getApplicationName()));
    }
    @Bean @ConditionalOnClass(name="feign.RequestInterceptor") EasyAtFeignInterceptor easyAtFeignInterceptor(HmacSigner signer){return new EasyAtFeignInterceptor(signer,props.getApplicationName());}

    @Bean @ConditionalOnProperty(prefix="easy-at.storage",name="type",havingValue="redis")
    JedisPool easyAtJedisPool(){
        EasyAtProperties.Storage.Redis r=props.getStorage().getRedis();
        redis.clients.jedis.JedisPoolConfig cfg=new redis.clients.jedis.JedisPoolConfig();
        cfg.setMaxTotal(r.getMaxTotal());
        return new redis.clients.jedis.JedisPool(cfg,r.getHost(),r.getPort(),r.getTimeoutMillis(),r.getPassword(),r.getDatabase());
    }
}
