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

    private final Storage storage = new Storage();
    private final Redis redis = new Redis();
    private final Lock lock = new Lock();
    private final Sql sql = new Sql();
    private final Recovery recovery = new Recovery();
    private final Cleanup cleanup = new Cleanup();
    private final Transport transport = new Transport();
    private final Management management = new Management();

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
