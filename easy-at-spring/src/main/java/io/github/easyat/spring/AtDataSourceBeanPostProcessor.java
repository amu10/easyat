package io.github.easyat.spring;

import io.github.easyat.core.AtTransactionManager;
import io.github.easyat.core.BranchRegistrar;
import io.github.easyat.core.GlobalLockManager;
import io.github.easyat.jdbc.AtDataSource;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Wraps eligible application DataSources with the easyAt JDBC proxy. */
public final class AtDataSourceBeanPostProcessor implements BeanPostProcessor {
    /** 延迟获取当前事务管理器（用 {@link ObjectProvider} 避免早期 Bean 依赖环）。 */
    private final ObjectProvider<AtTransactionManager> manager;

    /** 延迟获取全局锁管理器。 */
    private final ObjectProvider<GlobalLockManager> locks;

    /** 分支登记器，本地分支首次执行 DML 时由代理回调。 */
    private final BranchRegistrar registrar;

    /** 配置项来源（数据源排除列表、资源与 SQL 相关配置）。 */
    private final EasyAtProperties properties;

    public AtDataSourceBeanPostProcessor(
            ObjectProvider<AtTransactionManager> manager,
            ObjectProvider<GlobalLockManager> locks,
            BranchRegistrar registrar,
            EasyAtProperties properties) {
        this.manager = manager;
        this.locks = locks;
        this.registrar = registrar;
        this.properties = properties;
    }

    /**
     * 初始化前不做任何处理，原样返回 Bean。
     *
     * @param bean 待初始化的 Bean
     * @param beanName Bean 名称
     * @return 原 Bean（不改变）
     * @throws BeansException 不会抛出
     */
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName)
            throws BeansException {
        return bean;
    }

    /**
     * 初始化后把符合条件的数据源包装成 easyAt 的 {@link AtDataSource} 代理。 跳过：非 DataSource、已是
     * AtDataSource、被排除列表命中的、以及显式禁用的资源。
     *
     * @param bean 已初始化的 Bean
     * @param beanName Bean 名称
     * @return 原 Bean 或包装后的 {@link AtDataSource} 代理
     * @throws BeansException 不会抛出
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        // 只处理普通 DataSource，且避免对已包装的代理二次包装
        if (!(bean instanceof DataSource) || bean instanceof AtDataSource) return bean;
        // 排除列表中的数据源（如只读副本）不包装
        if (properties.getDatasourceExclude().contains(beanName)) return bean;
        EasyAtProperties.ResourceConfig resource = properties.getResources().get(beanName);
        // 该资源显式关闭时跳过
        if (resource != null && !resource.isEnabled()) return bean;
        // 资源 id 优先取显式配置，否则退回 Bean 名，保证每个数据源有唯一标识
        String resourceId =
                resource != null
                                && resource.getResourceId() != null
                                && !resource.getResourceId().trim().isEmpty()
                        ? resource.getResourceId().trim()
                        : beanName;
        // 用代理包裹真实数据源：注入事务管理器、全局锁、本地事务桥、分支登记器与最大影响行数
        return new AtDataSource(
                resourceId,
                (DataSource) bean,
                () -> manager.getObject(),
                () -> locks.getObject(),
                new SpringTransactionBridge(),
                properties.isRequireLocalTransaction(),
                registrar,
                properties.getSql().getMaxAffectedRows());
    }
}
