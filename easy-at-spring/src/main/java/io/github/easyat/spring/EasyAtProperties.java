package io.github.easyat.spring;

import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds the {@code easy-at.*} namespace and exposes safe production defaults. */
@ConfigurationProperties(prefix = "easy-at")
public class EasyAtProperties {
    /** Logical application name, used for headers, branch ownership and diagnostics. */
    private String applicationName = "unknown-service";

    /**
     * Enables stricter production checks: requires a local transaction, rejects missing HMAC
     * secret.
     */
    private boolean production = false;

    /** 事务/分支/undo 的存储配置（file / redis / jdbc）。 */
    private final Storage storage = new Storage();

    /** Redis 连接配置，供 Redis 存储与 Redis 锁管理器共享。 */
    private final Redis redis = new Redis();

    /** 全局锁管理器配置（file / redis）。 */
    private final Lock lock = new Lock();

    /** 本地 SQL 解析相关配置（严格模式、方言、最大影响行数）。 */
    private final Sql sql = new Sql();

    /** 恢复调度配置（开关、间隔、批大小、租约、最大重试）。 */
    private final Recovery recovery = new Recovery();

    /** 历史/锁清理配置（开关、间隔、批大小、各保留期）。 */
    private final Cleanup cleanup = new Cleanup();

    /** 跨服务传输安全配置（HMAC 密钥）。 */
    private final Transport transport = new Transport();

    /** 运维管理端点配置（开关、token）。 */
    private final Management management = new Management();

    /** 根 AT 事务灰度配置；只控制新 XID，不影响已有事务、恢复和回滚。 */
    private final Gray gray = new Gray();

    /**
     * Names of DataSource beans that must NOT be wrapped by the AT proxy (e.g. readonly replicas).
     */
    private List<String> datasourceExclude = new ArrayList<String>();

    /**
     * When set and no Spring local transaction is active, the proxy rejects DML instead of
     * degrading.
     */
    private Boolean requireLocalTransaction;

    /** Per-resource overrides keyed by DataSource bean name. */
    private Map<String, ResourceConfig> resources = new HashMap<String, ResourceConfig>();

    public boolean isRequireLocalTransaction() {
        return requireLocalTransaction != null ? requireLocalTransaction : production;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String v) {
        this.applicationName = v;
    }

    public boolean isProduction() {
        return production;
    }

    public void setProduction(boolean v) {
        this.production = v;
    }

    public void setRequireLocalTransaction(Boolean v) {
        this.requireLocalTransaction = v;
    }

    public Storage getStorage() {
        return storage;
    }

    /** Redis connection shared by Redis-backed storage and/or the Redis lock manager. */
    public Redis getRedis() {
        return redis;
    }

    public List<String> getDatasourceExclude() {
        return datasourceExclude;
    }

    public void setDatasourceExclude(List<String> v) {
        this.datasourceExclude = v;
    }

    public Lock getLock() {
        return lock;
    }

    public Sql getSql() {
        return sql;
    }

    public Recovery getRecovery() {
        return recovery;
    }

    public Cleanup getCleanup() {
        return cleanup;
    }

    public Transport getTransport() {
        return transport;
    }

    public Management getManagement() {
        return management;
    }

    public Gray getGray() {
        return gray;
    }

    public Map<String, ResourceConfig> getResources() {
        return resources;
    }

    public void setResources(Map<String, ResourceConfig> v) {
        this.resources = v;
    }

    public static class Storage {
        private String type = "file";
        private String fileDir = "./data/easy-at";

        public String getType() {
            return type;
        }

        public void setType(String v) {
            this.type = v;
        }

        public String getFileDir() {
            return fileDir;
        }

        public void setFileDir(String v) {
            this.fileDir = v;
        }
    }

    public static class Redis {
        private String host = "localhost";
        private int port = 6379;
        private String password;
        private int database = 0;
        private int timeoutMillis = 2000;
        private int maxTotal = 8;
        private Duration ttl = Duration.ofDays(30);

        public String getHost() {
            return host;
        }

        public void setHost(String v) {
            this.host = v;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int v) {
            this.port = v;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String v) {
            this.password = v;
        }

        public int getDatabase() {
            return database;
        }

        public void setDatabase(int v) {
            this.database = v;
        }

        public int getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(int v) {
            this.timeoutMillis = v;
        }

        public int getMaxTotal() {
            return maxTotal;
        }

        public void setMaxTotal(int v) {
            this.maxTotal = v;
        }

        /**
         * Redis 键的兜底 TTL：每次写入都会刷新，因此活跃事务永不到期。
         *
         * <p>它只是最后一道防线——正常情况下 {@code RedisCleanup} 会先按保留期把终态事务删掉。 没有它时，一旦清理器停摆，那些键会永久留在 Redis 里。
         */
        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration v) {
            this.ttl = v;
        }
    }

    public static class Lock {
        private String type = "file";
        private Duration waitTimeout = Duration.ofSeconds(3);
        private Duration lease = Duration.ofSeconds(30);

        public String getType() {
            return type;
        }

        public void setType(String v) {
            this.type = v;
        }

        public Duration getWaitTimeout() {
            return waitTimeout;
        }

        public void setWaitTimeout(Duration v) {
            this.waitTimeout = v;
        }

        public Duration getLease() {
            return lease;
        }

        public void setLease(Duration v) {
            this.lease = v;
        }
    }

    public static class Sql {
        private boolean strict = true;
        private String dialect;
        private int maxAffectedRows = 100;

        public boolean isStrict() {
            return strict;
        }

        public void setStrict(boolean v) {
            this.strict = v;
        }

        public String getDialect() {
            return dialect;
        }

        public void setDialect(String v) {
            this.dialect = v;
        }

        /** Maximum rows allowed for an explicit primary-key IN predicate. */
        public int getMaxAffectedRows() {
            return maxAffectedRows;
        }

        public void setMaxAffectedRows(int maxAffectedRows) {
            if (maxAffectedRows < 1)
                throw new IllegalArgumentException("easy-at.sql.max-affected-rows must be >= 1");
            this.maxAffectedRows = maxAffectedRows;
        }
    }

    public static class Recovery {
        private boolean enabled = true;
        private Duration interval = Duration.ofSeconds(10);
        private int batchSize = 100;
        private Duration lease = Duration.ofSeconds(30);
        private int maxRetries = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean v) {
            this.enabled = v;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration v) {
            this.interval = v;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int v) {
            this.batchSize = v;
        }

        public Duration getLease() {
            return lease;
        }

        public void setLease(Duration v) {
            this.lease = v;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int v) {
            this.maxRetries = v;
        }
    }

    public static class Cleanup {
        private boolean enabled = false;
        private Duration interval = Duration.ofMinutes(1);
        private int batchSize = 500;
        private Duration committedRetention = Duration.ofDays(7);
        private Duration rolledBackRetention = Duration.ofDays(30);
        private Duration expiredLockRetention = Duration.ofMinutes(10);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getCommittedRetention() {
            return committedRetention;
        }

        public void setCommittedRetention(Duration committedRetention) {
            this.committedRetention = committedRetention;
        }

        public Duration getRolledBackRetention() {
            return rolledBackRetention;
        }

        public void setRolledBackRetention(Duration rolledBackRetention) {
            this.rolledBackRetention = rolledBackRetention;
        }

        public Duration getExpiredLockRetention() {
            return expiredLockRetention;
        }

        public void setExpiredLockRetention(Duration expiredLockRetention) {
            this.expiredLockRetention = expiredLockRetention;
        }
    }

    public static class Transport {
        private String hmacSecret;

        public String getHmacSecret() {
            return hmacSecret;
        }

        public void setHmacSecret(String v) {
            this.hmacSecret = v;
        }
    }

    public static class Management {
        private boolean enabled = false;
        private String token;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean v) {
            this.enabled = v;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String v) {
            this.token = v;
        }
    }

    public static class Gray {
        /** OFF=停止新事务，GRAY=按规则分桶，FULL=全量。默认 FULL 保持向后兼容。 */
        private String mode = "FULL";

        private int defaultPercentage = 0;
        private Map<String, GrayRule> rules = new HashMap<String, GrayRule>();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public int getDefaultPercentage() {
            return defaultPercentage;
        }

        public void setDefaultPercentage(int percentage) {
            checkPercentage(percentage);
            this.defaultPercentage = percentage;
        }

        public Map<String, GrayRule> getRules() {
            return rules;
        }

        public void setRules(Map<String, GrayRule> rules) {
            this.rules = rules == null ? new HashMap<String, GrayRule>() : rules;
        }
    }

    public static class GrayRule {
        private int percentage;
        private String salt = "";
        private Set<String> whitelist = new HashSet<String>();
        private Set<String> blacklist = new HashSet<String>();

        public int getPercentage() {
            return percentage;
        }

        public void setPercentage(int percentage) {
            checkPercentage(percentage);
            this.percentage = percentage;
        }

        public String getSalt() {
            return salt;
        }

        public void setSalt(String salt) {
            this.salt = salt;
        }

        public Set<String> getWhitelist() {
            return whitelist;
        }

        public void setWhitelist(Set<String> whitelist) {
            this.whitelist = whitelist == null ? new HashSet<String>() : whitelist;
        }

        public Set<String> getBlacklist() {
            return blacklist;
        }

        public void setBlacklist(Set<String> blacklist) {
            this.blacklist = blacklist == null ? new HashSet<String>() : blacklist;
        }
    }

    private static void checkPercentage(int percentage) {
        if (percentage < 0 || percentage > 100)
            throw new IllegalArgumentException("easy-at.gray percentage must be between 0 and 100");
    }

    public static class ResourceConfig {
        private boolean enabled = true;
        private String resourceId;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean v) {
            this.enabled = v;
        }

        public String getResourceId() {
            return resourceId;
        }

        public void setResourceId(String v) {
            this.resourceId = v;
        }
    }
}
