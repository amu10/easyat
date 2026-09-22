package io.github.easyat.spring;

import io.github.easyat.core.*;
import io.github.easyat.jdbc.AtDataSource;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps every business {@link DataSource} in an {@link AtDataSource} so supported DML is
 * intercepted and undo logs / global locks / branch registration happen transparently.
 *
 * <p>DataSources explicitly disabled via {@code easy-at.resources.<beanName>.enabled=false} are
 * left untouched (e.g. a reporting/readonly replica). The framework's own tables are never
 * double-logged because {@code SqlUndoLogGenerator} skips any table prefixed with {@code easy_at_}.
 */
public final class AtDataSourceBeanPostProcessor implements BeanPostProcessor {
    private final AtTransactionManager manager; private final GlobalLockManager locks;
    private final BranchRegistrar registrar; private final EasyAtProperties properties;
    public AtDataSourceBeanPostProcessor(AtTransactionManager manager, GlobalLockManager locks){
        this(manager,locks,BranchRegistrar.NOOP,null);
    }
    public AtDataSourceBeanPostProcessor(AtTransactionManager manager, GlobalLockManager locks, BranchRegistrar registrar, EasyAtProperties properties){
        this.manager=manager;this.locks=locks;this.registrar=registrar==null?BranchRegistrar.NOOP:registrar;this.properties=properties;
    }
    @Override public Object postProcessBeforeInitialization(Object bean,String name)throws BeansException{return bean;}
    @Override public Object postProcessAfterInitialization(Object bean,String name)throws BeansException{
        if(bean instanceof AtDataSource || !(bean instanceof DataSource))return bean;
        if(properties!=null){
            EasyAtProperties.ResourceConfig rc=properties.getResources().get(name);
            if(rc!=null&&!rc.isEnabled())return bean; // opt-out this DataSource from AT proxying
        }
        return new AtDataSource(name,(DataSource)bean,manager,locks,new SpringTransactionBridge(),properties!=null&&properties.isRequireLocalTransaction(),registrar);
    }
}
