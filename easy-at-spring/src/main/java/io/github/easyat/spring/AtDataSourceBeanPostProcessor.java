package io.github.easyat.spring;

import io.github.easyat.core.AtTransactionManager;
import io.github.easyat.core.BranchRegistrar;
import io.github.easyat.core.GlobalLockManager;
import io.github.easyat.jdbc.AtDataSource;
import java.util.LinkedHashMap;
import java.util.Map;
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
     * 判断某个 DataSource Bean 是否参与 AT。
     *
     * <p>这是<b>唯一</b>的判定入口：数据源代理包装与 undo 仓库装配都必须用它，避免两边规则漂移—— 比如给被排除的只读副本也建了 undo 仓库，而该库并没有 {@code
     * easy_at_undo_log} 表，查询时必然报错。
     *
     * @param beanName DataSource 的 Bean 名
     * @param properties 配置
     * @return true 表示参与 AT（需要包装代理、需要 undo 仓库）
     */
    public static boolean isAtEligible(String beanName, EasyAtProperties properties) {
        if (properties.getDatasourceExclude().contains(beanName)) return false;
        EasyAtProperties.ResourceConfig resource = properties.getResources().get(beanName);
        return resource == null || resource.isEnabled();
    }

    /**
     * 取某个 DataSource 的 resourceId：优先显式配置，否则退回 Bean 名。
     *
     * <p>undo 记录靠 resourceId 定位自己属于哪个库（回滚时 {@code JdbcUndoExecutor} 就是按它选数据源）， 因此这里必须和代理包装用的是同一个值。
     *
     * @param beanName DataSource 的 Bean 名
     * @param properties 配置
     * @return resourceId
     */
    public static String resourceIdOf(String beanName, EasyAtProperties properties) {
        EasyAtProperties.ResourceConfig resource = properties.getResources().get(beanName);
        return resource != null
                        && resource.getResourceId() != null
                        && !resource.getResourceId().trim().isEmpty()
                ? resource.getResourceId().trim()
                : beanName;
    }

    /**
     * 构造「resourceId → DataSource」的查找表，供回滚执行器按 undo 记录的 resourceId 选库。
     *
     * <p>同时保留 Bean 名做 key（两个 key 指向同一个 DataSource）： Bean 名是历史行为，resourceId 是 undo
     * 记录真正携带的标识。用户一旦在配置里显式指定了 resourceId，只按 Bean 名索引就会查不到库， 回滚直接报 {@code Unknown resource}。
     *
     * @param sources Spring 注入的「Bean 名 → DataSource」
     * @param properties 配置
     * @return 同时按 Bean 名与 resourceId 索引的查找表
     */
    public static Map<String, DataSource> resourceKeyedSources(
            Map<String, DataSource> sources, EasyAtProperties properties) {
        Map<String, DataSource> keyed = new LinkedHashMap<String, DataSource>();
        if (sources == null) return keyed;
        for (Map.Entry<String, DataSource> e : sources.entrySet()) {
            String beanName = e.getKey();
            DataSource ds = e.getValue();
            keyed.put(beanName, ds);
            if (!isAtEligible(beanName, properties)) continue;
            String resourceId = resourceIdOf(beanName, properties);
            if (!resourceId.equals(beanName)) keyed.put(resourceId, ds);
        }
        return keyed;
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
        // 排除列表/显式禁用的数据源（如只读副本）不包装。
        // 判定必须与 undo 仓库装配共用同一套规则：给被排除的库也建 undo 仓库，会因该库没有
        // easy_at_undo_log 表而在 load/deleteByXid 时报错。
        if (!isAtEligible(beanName, properties)) return bean;
        // 资源 id 优先取显式配置，否则退回 Bean 名，保证每个数据源有唯一标识
        String resourceId = resourceIdOf(beanName, properties);
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
