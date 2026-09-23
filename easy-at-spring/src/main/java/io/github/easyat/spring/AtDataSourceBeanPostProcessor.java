package io.github.easyat.spring;

import io.github.easyat.core.AtTransactionManager;
import io.github.easyat.core.BranchRegistrar;
import io.github.easyat.core.GlobalLockManager;
import io.github.easyat.jdbc.AtDataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;

/** Wraps eligible application DataSources with the easyAt JDBC proxy. */
public final class AtDataSourceBeanPostProcessor implements BeanPostProcessor {
    private final ObjectProvider<AtTransactionManager> manager;
    private final ObjectProvider<GlobalLockManager> locks;
    private final BranchRegistrar registrar;
    private final EasyAtProperties properties;

    public AtDataSourceBeanPostProcessor(ObjectProvider<AtTransactionManager> manager,ObjectProvider<GlobalLockManager> locks,BranchRegistrar registrar,EasyAtProperties properties){
        this.manager=manager;this.locks=locks;this.registrar=registrar;this.properties=properties;
    }

    @Override public Object postProcessBeforeInitialization(Object bean,String beanName)throws BeansException{return bean;}

    @Override public Object postProcessAfterInitialization(Object bean,String beanName)throws BeansException{
        if(!(bean instanceof DataSource)||bean instanceof AtDataSource)return bean;
        if(properties.getDatasourceExclude().contains(beanName))return bean;
        EasyAtProperties.ResourceConfig resource=properties.getResources().get(beanName);
        if(resource!=null&&!resource.isEnabled())return bean;
        String resourceId=resource!=null&&resource.getResourceId()!=null&&!resource.getResourceId().trim().isEmpty()?resource.getResourceId().trim():beanName;
        return new AtDataSource(resourceId,(DataSource)bean,()->manager.getObject(),()->locks.getObject(),new SpringTransactionBridge(),properties.isRequireLocalTransaction(),registrar);
    }
}
