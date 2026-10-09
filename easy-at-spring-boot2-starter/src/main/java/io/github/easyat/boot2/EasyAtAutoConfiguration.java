package io.github.easyat.boot2;

import io.github.easyat.core.*;
import io.github.easyat.jdbc.*;
import io.github.easyat.spring.*;
import io.github.easyat.storage.file.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Paths;
import java.util.Map;
import javax.servlet.Filter;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * Wires the embedded AT transaction framework into a Spring Boot 2 application: configuration
 * binding, metrics, dialect-aware undo, local-transaction bridge, multi-instance recovery lease,
 * branch coordination, signed cross-service transport, management/ops API and MDC logging.
 */
@AutoConfiguration
@ConditionalOnClass(EasyAtAspect.class)
@EnableConfigurationProperties(EasyAtProperties.class)
public class EasyAtAutoConfiguration {
    /** 绑定的 easy-at 配置属性（来自 {@code EasyAtProperties}）。 */
    private final EasyAtProperties props;

    /** 本实例标识：{@code 应用名@主机名}，恢复/清理调度器抢租约时用作 owner 去重。 */
    private final String owner;

    /**
     * 构造自动配置：绑定配置属性，并基于「应用名 + 主机名」生成本实例 owner 标识。
     *
     * <p>owner 用于多实例部署下恢复/清理调度器抢占租约，避免同一全局事务被多个实例同时驱动。
     *
     * @param props easy-at 配置属性
     */
    public EasyAtAutoConfiguration(EasyAtProperties props) {
        this.props = props;
        String h = "unknown";
        try {
            h = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
        }
        this.owner = props.getApplicationName() + "@" + h;
    }

    /** HMAC 签名器：用于跨服务传输 XID 时的请求签名与验签（未配置秘钥时签名器不生效）。 */
    @Bean
    @ConditionalOnMissingBean
    HmacSigner easyAtHmacSigner() {
        return new HmacSigner(props.getTransport().getHmacSecret());
    }

    /** Micrometer 指标收集器：汇总分支注册、回滚等运行指标，注册表缺失时退化为空指标。 */
    @Bean
    @ConditionalOnMissingBean
    EasyAtMetrics easyAtMetrics(ObjectProvider<MeterRegistry> registry) {
        return new EasyAtMetrics(registry.getIfAvailable());
    }

    /** undo 数据编解码器（Jackson 实现）：负责 before/after image 与参数的序列化，可叠加加密/脱敏。 */
    @Bean
    @ConditionalOnMissingBean
    UndoDataCodec easyAtUndoDataCodec(
            ObjectProvider<UndoDataEncryptor> encryptor, ObjectProvider<UndoDataMasker> masker) {
        return new JacksonUndoDataCodec(encryptor.getIfAvailable(), masker.getIfAvailable());
    }

    /** JDBC 存储后端：全局事务/分支/锁全部落在业务库（storage.type=jdbc 时启用）。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easy-at.storage", name = "type", havingValue = "jdbc")
    AtRepository jdbcAtRepository(DataSource dataSource, UndoDataCodec codec) {
        return new JdbcAtRepository(dataSource, codec);
    }

    /** 文件存储后端：全局事务状态落本地文件，作为默认存储（storage.type=file 或缺失时启用）。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "easy-at.storage",
            name = "type",
            havingValue = "file",
            matchIfMissing = true)
    AtRepository fileAtRepository() {
        return new FileAtRepository(Paths.get(props.getStorage().getFileDir()));
    }

    /** JDBC 全局锁管理器：分布式全局锁记录在业务库（lock.type=jdbc 时启用）。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easy-at.lock", name = "type", havingValue = "jdbc")
    GlobalLockManager jdbcAtLockManager(DataSource dataSource) {
        return new JdbcGlobalLockManager(
                dataSource,
                props.getLock().getLease().toMillis(),
                props.getLock().getWaitTimeout().toMillis());
    }

    /** 文件全局锁管理器：全局锁落本地文件，作为默认（lock.type=file 或缺失时启用）。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "easy-at.lock",
            name = "type",
            havingValue = "file",
            matchIfMissing = true)
    GlobalLockManager fileAtLockManager() {
        return new FileGlobalLockManager(
                Paths.get(props.getStorage().getFileDir() + "-locks"),
                props.getLock().getWaitTimeout().toMillis());
    }

    /** undo 执行器：按「数据源名 → DataSource」映射，对指定资源执行回滚 SQL。 */
    @Bean
    @ConditionalOnMissingBean
    UndoExecutor easyAtUndoExecutor(Map<String, DataSource> sources) {
        return new JdbcUndoExecutor(sources);
    }

    /**
     * 混合存储模式的 undo 仓库：undo 必须落在业务库，与业务本地事务一起提交/回滚。
     *
     * @param dataSource 业务库数据源
     * @param codec undo 数据编解码器
     * @return 基于 JDBC 的 undo 仓库
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easy-at.storage", name = "type", havingValue = "hybrid")
    UndoRepository jdbcUndoRepository(DataSource dataSource, UndoDataCodec codec) {
        // 混合存储：undo 必须在业务库，跟着业务本地事务一起提交/回滚。
        return new JdbcUndoRepository(dataSource, codec);
    }

    /**
     * 核心事务管理器：协调全局事务的开启/提交/回滚、分支注册、全局锁与 undo 补偿。
     *
     * @param r 全局事务/分支/锁存储仓库
     * @param u undo 执行器
     * @param l 全局锁管理器
     * @param undoRepository 可选：混合存储下的 undo 仓库（缺失则为 null）
     * @return AT 事务管理器
     */
    @Bean
    @ConditionalOnMissingBean
    AtTransactionManager easyAtManager(
            AtRepository r,
            UndoExecutor u,
            GlobalLockManager l,
            ObjectProvider<UndoRepository> undoRepository) {
        // owner 必须与 AtRecoveryScheduler 一致：调度器先抢租约再驱动回滚，同 owner 才可重入。
        // 回滚期用一份独立的（更长）租约，避免长补偿过程中被误判过期而遭他人接管。
        return new AtTransactionManager(
                r,
                u,
                l,
                props.getRecovery().getMaxRetries(),
                owner,
                Math.max(
                        props.getRecovery().getLease().toMillis(),
                        AtTransactionManager.DEFAULT_ROLLBACK_LEASE_MILLIS),
                undoRepository.getIfAvailable());
    }

    /** JDBC 分支仓库：分支事务记录落业务库（storage.type=jdbc 时启用）。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easy-at.storage", name = "type", havingValue = "jdbc")
    BranchRepository jdbcBranchRepository(DataSource dataSource) {
        return new JdbcBranchRepository(dataSource);
    }

    /** JDBC 清理器：负责物理删除业务库中已提交/已回滚/过期的全局事务与锁记录。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easy-at.storage", name = "type", havingValue = "jdbc")
    JdbcAtCleaner jdbcAtCleaner(DataSource dataSource) {
        return new JdbcAtCleaner(dataSource);
    }

    /**
     * 清理调度器：定时回收已提交/已回滚/过期的全局事务与锁记录（仅 JDBC 存储启用）。
     *
     * @param cleaner JDBC 清理器
     * @return 已启动的清理调度器（关闭时由 destroyMethod 触发）
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcAtCleaner.class)
    @ConditionalOnProperty(prefix = "easy-at.cleanup", name = "enabled", havingValue = "true")
    AtCleanupScheduler easyAtCleanupScheduler(JdbcAtCleaner cleaner) {
        EasyAtProperties.Cleanup cleanup = props.getCleanup();
        AtCleanupScheduler scheduler =
                new AtCleanupScheduler(
                        cleaner,
                        cleanup.getInterval().toMillis(),
                        cleanup.getCommittedRetention().toMillis(),
                        cleanup.getRolledBackRetention().toMillis(),
                        cleanup.getExpiredLockRetention().toMillis(),
                        cleanup.getBatchSize());
        scheduler.start();
        return scheduler;
    }

    /** 文件分支仓库：分支事务记录落本地文件（storage.type=file 或缺失时启用）。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "easy-at.storage",
            name = "type",
            havingValue = "file",
            matchIfMissing = true)
    BranchRepository fileBranchRepository() {
        return new FileBranchRepository(Paths.get(props.getStorage().getFileDir() + "-branches"));
    }

    /**
     * 分支协调器：二阶段提交时驱动各分支的 commit/rollback，并对协调请求做 HMAC 验签。
     *
     * @param branches 分支仓库
     * @param manager 事务管理器
     * @param signer HMAC 签名器
     * @param metrics 指标收集器
     * @return 分支协调器
     */
    @Bean
    @ConditionalOnMissingBean
    BranchCoordinator branchCoordinator(
            BranchRepository branches,
            AtTransactionManager manager,
            HmacSigner signer,
            EasyAtMetrics metrics) {
        return new BranchCoordinator(
                branches, manager, signer, props.getApplicationName(), metrics);
    }

    /**
     * 跨服务传输安全组件：对 XID 传播做 HMAC 签名/验签，并区分生产/非生产环境策略。
     *
     * @param signer HMAC 签名器
     * @return 传输安全组件
     */
    @Bean
    @ConditionalOnMissingBean
    EasyAtTransportSecurity easyAtTransportSecurity(HmacSigner signer) {
        return new EasyAtTransportSecurity(signer, props.isProduction());
    }

    /**
     * 分支协调服务：把协调器与传输安全封装为对外的协调接口（注册/提交/回滚）。
     *
     * @param coordinator 分支协调器
     * @param security 传输安全组件
     * @return 协调服务
     */
    @Bean
    @ConditionalOnMissingBean
    CoordinationService coordinationService(
            BranchCoordinator coordinator, EasyAtTransportSecurity security) {
        return new CoordinationService(coordinator, security);
    }

    /**
     * 对账服务：比对全局事务状态与分支状态，发现「全局已提交但分支未提交」等不一致， 供影子运行期间灰度校验（固定 60s 超时、每批 1000 条）。
     *
     * @param r 全局事务仓库
     * @param branches 分支仓库（可选，缺失时为 null）
     * @param locks 全局锁管理器
     * @return 对账服务
     */
    @Bean
    @ConditionalOnMissingBean
    ReconciliationService reconciliationService(
            AtRepository r,
            @Autowired(required = false) BranchRepository branches,
            GlobalLockManager locks) {
        return new ReconciliationService(
                r, branches, locks, props.getApplicationName(), 60000L, 1000);
    }

    /**
     * 管理/运维服务：提供事务查询、列表、对账、人工重试/回滚等能力，受 token 保护。
     *
     * @param r 全局事务仓库
     * @param m 事务管理器（用于人工触发回滚/重试）
     * @param metrics 指标收集器
     * @param codec undo 数据编解码器（用于解析/展示 image）
     * @param reconciliation 对账服务（注入到管理服务的可选能力）
     * @return 管理/运维服务
     */
    @Bean
    @ConditionalOnMissingBean
    ManagementService managementService(
            AtRepository r,
            AtTransactionManager m,
            EasyAtMetrics metrics,
            UndoDataCodec codec,
            ReconciliationService reconciliation) {
        ManagementService s =
                new ManagementService(
                        r,
                        m,
                        metrics,
                        props.getManagement().isEnabled(),
                        props.getManagement().getToken(),
                        codec);
        s.setReconciliationService(reconciliation);
        return s;
    }

    /**
     * 恢复调度器：定时扫描过期/悬挂的全局事务并驱动回滚补偿，多实例靠 owner 抢租约互斥。
     *
     * @param r 全局事务仓库
     * @param m 事务管理器
     * @param coordinator 分支协调器
     * @param metrics 指标收集器
     * @return 已启动的恢复调度器
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "easy-at.recovery",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    AtRecoveryScheduler easyAtRecoveryScheduler(
            AtRepository r,
            AtTransactionManager m,
            BranchCoordinator coordinator,
            EasyAtMetrics metrics) {
        AtRecoveryScheduler s =
                new AtRecoveryScheduler(
                        r,
                        m,
                        coordinator,
                        metrics,
                        owner,
                        props.getRecovery().getInterval().toMillis(),
                        props.getRecovery().getBatchSize(),
                        props.getRecovery().getLease().toMillis());
        s.start();
        return s;
    }

    /**
     * 分支重试调度器：定时重试仍处于重试中状态的分支事务，直至达到最大重试次数。
     *
     * @param branches 分支仓库
     * @param coordinator 分支协调器
     * @param metrics 指标收集器
     * @return 已启动的分支重试调度器
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "easy-at.recovery",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    BranchRetryScheduler branchRetryScheduler(
            BranchRepository branches, BranchCoordinator coordinator, EasyAtMetrics metrics) {
        BranchRetryScheduler s =
                new BranchRetryScheduler(
                        branches,
                        coordinator,
                        props.getRecovery().getInterval().toMillis(),
                        props.getRecovery().getBatchSize(),
                        props.getRecovery().getMaxRetries(),
                        metrics);
        s.start();
        return s;
    }

    /**
     * AT 切面：拦截事务注解，驱动全局事务的开启/提交/回滚，并在分支侧登记数据源与分支信息。
     *
     * @param m 事务管理器
     * @param c 分支协调器
     * @param metrics 指标收集器
     * @return AT 切面
     */
    @Bean
    @ConditionalOnMissingBean
    AtGrayDecider easyAtGrayDecider(EasyAtProperties properties) {
        return new PropertiesAtGrayDecider(properties);
    }

    @Bean
    EasyAtAspect easyAtAspect(
            AtTransactionManager m,
            BranchCoordinator c,
            EasyAtMetrics metrics,
            AtGrayDecider grayDecider,
            EasyAtProperties properties) {
        return new EasyAtAspect(m, c, metrics, grayDecider, properties);
    }

    /**
     * 数据源后置处理器（静态 @Bean）：把每个 {@link javax.sql.DataSource} 包装为能感知 AT 的代理， 在连接上挂载全局锁与分支登记逻辑。
     *
     * <p>分支登记器用 Supplier 延迟获取分支仓库，规避与分支仓库 Bean 的初始化顺序依赖。
     *
     * @param m 事务管理器（延迟获取）
     * @param l 全局锁管理器（延迟获取）
     * @param branchRepo 分支仓库（延迟获取）
     * @param p 配置属性
     * @return 数据源后置处理器
     */
    @Bean
    static AtDataSourceBeanPostProcessor easyAtDataSourceBeanPostProcessor(
            ObjectProvider<AtTransactionManager> m,
            ObjectProvider<GlobalLockManager> l,
            ObjectProvider<BranchRepository> branchRepo,
            EasyAtProperties p) {
        BranchRegistrar registrar =
                new DefaultBranchRegistrar(
                        new java.util.function.Supplier<BranchRepository>() {
                            public BranchRepository get() {
                                return branchRepo.getIfAvailable();
                            }
                        },
                        p.getApplicationName());
        return new AtDataSourceBeanPostProcessor(m, l, registrar, p);
    }

    /** Web 协调控制器：仅在 Web 应用下装配，暴露分支注册/提交/回滚的 HTTP 端点。 */
    @Bean
    @ConditionalOnWebApplication
    EasyAtCoordinationController easyAtCoordinationController(CoordinationService service) {
        return new EasyAtCoordinationController(service);
    }

    /** Web 管理控制器：仅当 management.enabled=true 时装配，暴露运维 HTTP 端点。 */
    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnProperty(prefix = "easy-at.management", name = "enabled", havingValue = "true")
    EasyAtManagementController easyAtManagementController(ManagementService service) {
        return new EasyAtManagementController(service);
    }

    /**
     * XID 传播过滤器注册：把 {@link AtXidFilter} 注册到 Servlet 容器，order=-100 保证尽早执行。
     *
     * @param m 事务管理器
     * @param security 传输安全组件（可为 null）
     * @return 过滤器注册 Bean
     */
    @Bean
    @ConditionalOnClass(Filter.class)
    FilterRegistrationBean<AtXidFilter> easyAtXidFilter(
            AtTransactionManager m, EasyAtTransportSecurity security) {
        FilterRegistrationBean<AtXidFilter> b =
                new FilterRegistrationBean<AtXidFilter>(new AtXidFilter(m, security));
        b.setOrder(-100);
        return b;
    }

    /** MDC 过滤器注册：把 {@link EasyAtMdcFilter} 注册到 Servlet 容器，order=-90。 */
    @Bean
    @ConditionalOnClass(Filter.class)
    FilterRegistrationBean<EasyAtMdcFilter> easyAtMdcFilter() {
        FilterRegistrationBean<EasyAtMdcFilter> b =
                new FilterRegistrationBean<EasyAtMdcFilter>(new EasyAtMdcFilter());
        b.setOrder(-90);
        return b;
    }

    /**
     * RestTemplate 定制器：为应用内所有 {@link org.springframework.web.client.RestTemplate} 自动挂上 XID 传播拦截器。
     *
     * @param signer HMAC 签名器
     * @return RestTemplate 定制器
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.web.client.RestTemplate")
    RestTemplateCustomizer easyAtRestTemplateCustomizer(HmacSigner signer) {
        return new EasyAtRestTemplateCustomizer(
                new AtRestTemplateInterceptor(signer, props.getApplicationName()));
    }

    /**
     * WebClient 定制器：为应用内 WebClient 自动挂上 XID 传播拦截器（响应式场景）。
     *
     * <p>返回类型声明为 {@code Object} 而非具体类型，是为了在 WebClient 不存在时 该 Bean 仍能以宽松类型注册、不触发类加载失败。
     *
     * @param signer HMAC 签名器
     * @return WebClient 定制器
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.web.reactive.function.client.WebClient")
    Object easyAtWebClientCustomizer(HmacSigner signer) {
        return WebClientPropagator.createCustomizer(signer, props.getApplicationName());
    }
}
