# easy-at-db-tests

在**真实 MySQL / PostgreSQL / Redis** 上验证 easyAt。

这个模块刻意**不在默认 reactor 里**（只在 `-Pdbtest` 时加入），因为它需要 Docker 或一个外部真实实例——
它不能给普通 `mvn verify` 添负担，更不能让没有 Docker 的环境构建变红。

## 为什么必须存在

主模块的 59 个用例全部跑在 H2 内存库上。但生产用的是 MySQL / PostgreSQL / Redis，而以下部分
**从未在真实实例上执行过**：

| 未验证项 | 为什么 H2 盖不住 |
|---|---|
| `MysqlAtSqlDialect` / `PostgresAtSqlDialect` 方言分支 | H2 用的是 `GenericAtSqlDialect` |
| 通过 JDBC 元数据取主键、列标签大小写 | PG 默认返回**小写**标签，MySQL/H2(MySQL模式)不同 |
| `before/after image` 的二进制列 | MySQL 是 BLOB、PG 是 **BYTEA**，读写路径不同 |
| 事务隔离级别与行锁语义 | H2 与 InnoDB/PG 的 MVCC 完全不同 |
| Redis 存储的状态 CAS Lua 脚本 | 此前只有 1 个自动配置冒烟用例，Lua 逻辑从没真跑过 |
| 协调表 DDL 的可行性 | MySQL InnoDB 有**主键长度上限**（见下） |

## 首次运行就已经暴露的缺陷

真实库第一次跑就发现 3 个 H2 上永远看不见的问题，均已修复（详见根目录 `PRODUCTION_GAPS.md` §13）：

| 数据库 | 缺陷 | 后果 |
|---|---|---|
| PostgreSQL | `setObject` 把 Long 主键当 varchar 发送 | **PG 上回滚 100% 失败**（`bigint = character varying`） |
| Redis | 加锁 Lua 里 `lease`(number) 与 `now`(string) 比较 | `eval` 抛异常，既拿不到锁也拿不到冲突信号 |
| jedis | 与 jedis 4.x/5.x 二进制不兼容 | 运行期 `NoSuchMethodError`（**未修**，见下） |

## jedis 版本兼容性验证（已修，可回归）

Redis 模块过去按 jedis 3.8.0 编译，运行期遇到 4.x/5.x/6.x 会抛
`NoSuchMethodError: Jedis.hset(String, Map)`——因为写命令的返回值在 4.x 起从 `Long` 改成了 `long`。

修法是**只调用跨版本签名稳定的 API**（写全走 Lua `eval`，读只用 `hget/hgetAll/smembers/get`），
编译版本升到 **4.4.6**。使用者被 BOM 换成 3.8.0（Spring Boot 2.7）或 6.0.0（Spring Boot 3.5）都能跑。

本模块提供三个 profile 用于回归：

```shell
mvn -o -Pdbtest,jedis6 -pl easy-at-db-tests -am test -Dtest=RedisRealStorageIT \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Deasyat.it.redis.host=127.0.0.1 -Deasyat.it.redis.port=6379 \
    -Deasyat.it.redis.password='Redis,./' -Deasyat.it.redis.database=5
```

已在本地真实 Redis 上实测：**jedis 3.8.0 / 4.4.6（默认）/ 5.2.0 / 6.0.0 四档各 3/3 全绿**。

## 已知的生产坑位（真库才能发现）

`easy_at_lock` 的主键是 `(resource_id, table_name, pk_value)`。如果按常见写法用
`VARCHAR(255)+VARCHAR(255)+VARCHAR(512)`，在 MySQL utf8mb4 下是 `(255+255+512)×4 = 4096` 字节，
超过 InnoDB 单索引 3072 字节上限，会直接失败：

```
ERROR 1071: Specified key was too long; max key length is 3072 bytes
```

MySQL 上应使用更短的锁列（本模块用 `100/100/200`），或显式声明更窄的字符集。

## 跑法一：Testcontainers（需要 Docker）

```shell
mvn -Pdbtest test
```

首次会拉取 `mysql:8.0` / `postgres:16-alpine` / `redis:7-alpine` 镜像。

## 跑法二：指向已有实例（不需要 Docker）

内网已有数据库时最省事，用系统属性指过去即可：

```shell
mvn -Pdbtest test \
  -Deasyat.it.mysql.url=jdbc:mysql://192.168.1.10:3306/easyat \
  -Deasyat.it.mysql.user=root \
  -Deasyat.it.mysql.password=secret \
  -Deasyat.it.postgres.url=jdbc:postgresql://192.168.1.10:5432/easyat \
  -Deasyat.it.postgres.user=postgres \
  -Deasyat.it.postgres.password=secret \
  -Deasyat.it.redis.host=192.168.1.10 \
  -Deasyat.it.redis.port=6379 \
  -Deasyat.it.redis.password=secret \
  -Deasyat.it.redis.database=5
```

### ⚠️ 指向共享实例前的两条硬性要求

用例为了拿到干净状态会做**破坏性操作**，指向开发机/共享实例时必须先隔离：

1. **MySQL 用一个空 schema**，不要指业务库。用例会执行
   `DROP TABLE IF EXISTS t_account / easy_at_global / easy_at_undo_log / easy_at_lock`，
   然后重建——业务库里若有同名表会被**直接删掉**。
   ```sql
   CREATE DATABASE IF NOT EXISTS easy_at_it DEFAULT CHARACTER SET utf8mb4;
   ```
2. **Redis 用非 0 的 db 编号**。用例会 `FLUSHDB`（只清当前 db），默认 db 0 通常是业务缓存。
   务必加 `-Deasyat.it.redis.database=5`（或环境变量 `EASYAT_IT_REDIS_DATABASE`）。

本机示例（MySQL 5.7 + 本地 Redis，均按上面做过隔离）：

```shell
mvn -o -Pdbtest -pl easy-at-db-tests -am test \
  -Deasyat.it.mysql.url="jdbc:mysql://127.0.0.1:3306/easy_at_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai" \
  -Deasyat.it.mysql.user=root -Deasyat.it.mysql.password=root \
  -Deasyat.it.redis.host=127.0.0.1 -Deasyat.it.redis.port=6379 \
  -Deasyat.it.redis.password='Redis,./' -Deasyat.it.redis.database=5
```

没有指定 `easyat.it.postgres.*` 时，PostgreSQL 用例会退回 Testcontainers（有 Docker 就跑，没 Docker 就跳过）。

本仓库根目录的 `docker-compose.yml` 也可以直接起一套：

```shell
docker compose -f easy-at-db-tests/docker-compose.yml up -d
mvn -Pdbtest test \
  -Deasyat.it.mysql.url=jdbc:mysql://localhost:13306/easyat -Deasyat.it.mysql.user=easyat -Deasyat.it.mysql.password=easyat \
  -Deasyat.it.postgres.url=jdbc:postgresql://localhost:15432/easyat -Deasyat.it.postgres.user=easyat -Deasyat.it.postgres.password=easyat \
  -Deasyat.it.redis.host=localhost -Deasyat.it.redis.port=16379
docker compose -f easy-at-db-tests/docker-compose.yml down -v
```

## 环境变量（等价写法）

`EASYAT_IT_MYSQL_URL`、`EASYAT_IT_POSTGRES_URL`、`EASYAT_IT_REDIS_HOST`、`EASYAT_IT_REDIS_PORT`、
`EASYAT_IT_REDIS_PASSWORD`、`EASYAT_IT_REDIS_DATABASE`。

对应的账密属性是 `easyat.it.mysql.user/password`、`easyat.it.postgres.user/password`
（这两个只有系统属性形式，没有环境变量形式——避免把口令写进 shell 的历史/CI 环境）。

## 没有可执行环境时

测试会**跳过**而不是失败（抛 `TestAbortedException`），构建保持绿色，并打印跳过原因。
