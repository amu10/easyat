package io.github.easyat.boot2;
import io.github.easyat.core.*; import io.github.easyat.jdbc.*; import io.github.easyat.spring.*; import io.github.easyat.storage.file.*; import org.springframework.boot.autoconfigure.condition.*; import org.springframework.boot.web.servlet.FilterRegistrationBean; import org.springframework.context.annotation.*; import javax.sql.DataSource; import java.nio.file.Paths; import java.util.Map;
@Configuration(proxyBeanMethods=false) @ConditionalOnClass(EasyAtAspect.class)
public class EasyAtAutoConfiguration {
 @Bean @ConditionalOnMissingBean @ConditionalOnProperty(prefix="easy-at.storage",name="type",havingValue="jdbc") AtRepository jdbcAtRepository(DataSource dataSource){return new JdbcAtRepository(dataSource);}
 @Bean @ConditionalOnMissingBean @ConditionalOnProperty(prefix="easy-at.storage",name="type",havingValue="file",matchIfMissing=true) AtRepository easyAtRepository(){return new FileAtRepository(Paths.get("./data/easy-at"));}
 @Bean @ConditionalOnMissingBean @ConditionalOnProperty(prefix="easy-at.lock",name="type",havingValue="jdbc") GlobalLockManager jdbcAtLockManager(DataSource dataSource){return new JdbcGlobalLockManager(dataSource,30000L);}
 @Bean @ConditionalOnMissingBean @ConditionalOnProperty(prefix="easy-at.lock",name="type",havingValue="file",matchIfMissing=true) GlobalLockManager easyAtLockManager(){return new FileGlobalLockManager(Paths.get("./data/easy-at-locks"));}
 @Bean @ConditionalOnMissingBean UndoExecutor easyAtUndoExecutor(Map<String,DataSource> sources){return new JdbcUndoExecutor(sources);}
 @Bean @ConditionalOnMissingBean AtTransactionManager easyAtManager(AtRepository r,UndoExecutor u,GlobalLockManager l){return new AtTransactionManager(r,u,l,20);}
 @Bean(destroyMethod="close") @ConditionalOnMissingBean AtRecoveryScheduler easyAtRecoveryScheduler(AtRepository r,AtTransactionManager m){AtRecoveryScheduler scheduler=new AtRecoveryScheduler(r,m,5000L,100);scheduler.start();return scheduler;}
 @Bean EasyAtAspect easyAtAspect(AtTransactionManager m){return new EasyAtAspect(m);}
 @Bean static AtDataSourceBeanPostProcessor easyAtDataSourceBeanPostProcessor(AtTransactionManager m,GlobalLockManager l){return new AtDataSourceBeanPostProcessor(m,l);}
 @Bean @ConditionalOnClass(FilterRegistrationBean.class) FilterRegistrationBean<AtXidFilter> easyAtXidFilter(AtTransactionManager m){FilterRegistrationBean<AtXidFilter> b=new FilterRegistrationBean<AtXidFilter>(new AtXidFilter(m));b.setOrder(-100);return b;}
}
