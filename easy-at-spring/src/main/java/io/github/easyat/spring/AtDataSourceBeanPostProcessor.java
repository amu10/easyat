package io.github.easyat.spring;

import io.github.easyat.core.*;
import io.github.easyat.jdbc.AtDataSource;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

public final class AtDataSourceBeanPostProcessor implements BeanPostProcessor {
    private final AtTransactionManager manager; private final GlobalLockManager locks;
    public AtDataSourceBeanPostProcessor(AtTransactionManager manager, GlobalLockManager locks){this.manager=manager;this.locks=locks;}
    @Override public Object postProcessBeforeInitialization(Object bean,String name)throws BeansException{return bean;}
    @Override public Object postProcessAfterInitialization(Object bean,String name)throws BeansException{
        if(bean instanceof AtDataSource || !(bean instanceof DataSource))return bean;
        return new AtDataSource(name,(DataSource)bean,manager,locks);
    }
}
