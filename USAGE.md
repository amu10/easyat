# easyAt 使用说明与示例

> 从零接入 easyAt 的完整指南：依赖 → 配置 → 单服务示例 → 跨服务示例 → 管理运维 → 排查。
> 代码导读见 [CODE_GUIDE.md](./CODE_GUIDE.md)，生产就绪度见 [PRODUCTION_GAPS.md](./PRODUCTION_GAPS.md)。

## 1. 快速开始

### 1.1 引入依赖

```xml
<!-- Spring Boot 3.x（JDK 17+） -->
<dependency>
    <groupId>io.github.easyat</groupId>
    <artifactId>easy-at-spring-boot3-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Spring Boot 2.7 项目改用 `easy-at-spring-boot2-starter`。

> Starter 已传递引入 `easy-at-spring`、`easy-at-storage-file`、`spring-boot-starter-aop`、`spring-boot-starter-jdbc`。
> Redis 存储需要额外引入：
> ```xml
> <dependency>
>     <groupId>io.github.easyat</groupId>
>     <artifactId>easy-at-storage-redis</artifactId>
>     <version>0.1.0-SNAPSHOT</version>
> </dependency>
> ```

### 1.2 最小配置（单机开发，开箱即用）

```yaml
easy-at:
  application-name: order-service
```

默认使用 **File 存储**，适合本地跑通流程。启动后，给业务方法加 `@EasyAtTransactional` 即可。

## 2. 单服务示例（含完整代码）

### 2.1 场景：订单创建扣减账户

```java
import io.github.easyat.annotation.EasyAtTransactional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderMapper orderMapper;
    private final AccountMapper accountMapper;

    public OrderService(OrderMapper orderMapper, AccountMapper accountMapper) {
        this.orderMapper = orderMapper;
        this.accountMapper = accountMapper;
    }

    /**
     * @EasyAtTransactional 是「外层」分布式事务边界；
     * @Transactional 是「内层」本地事务，保证业务 DML 与 undo log 同连接、同提交。
     */
    @EasyAtTransactional(name = "create-order", timeout = 30000)
    @Transactional
    public void createOrder(long orderId, long userId, long amount) {
        orderMapper.insert(orderId, userId, amount);   // INSERT INTO t_order ...
        accountMapper.decrease(userId, amount);         // UPDATE t_account SET balance=balance-? WHERE id=?
    }
}
```

### 2.2 关键约定

- **表必须有单列主键**；`UPDATE`/`DELETE` 的 `WHERE` 必须精确匹配主键。
- **INSERT 必须显式带主键**（首版不支持自增主键生成）。
- 方法外层建议叠加 `@Transactional`（生产模式 `easy-at.production=true` 时会强制要求本地事务）。

### 2.3 效果

- 方法正常返回 → 全局事务提交，锁释放。
- 方法抛异常 → undo 逆序执行，数据回滚。
- 回滚时若发现数据已被并发改动（脏写）→ 转 `DIRTY_WRITE`，不覆盖，留待人工处理。

## 3. 配置项全解

```yaml
easy-at:
  application-name: order-service      # 应用名，用于 header、分支归属、诊断
  production: false                    # true=生产模式：强制本地事务 + 强制 HMAC 密钥

  # ---- 事务存储：file / jdbc / redis ----
  storage:
    type: file                         # 默认 file；生产用 jdbc 或 redis
    file-dir: ./data/easy-at
    redis:                             # type=redis 时生效
      host: localhost
      port: 6379
      password: ""
      database: 0

  # ---- 全局行锁：file / jdbc / redis ----
  lock:
    type: file
    wait-timeout: 3s                   # 拿不到锁时的最大等待时间
    lease: 30s                         # 锁租约时长

  # ---- SQL ----
  sql:
    strict: true                       # true=不支持的 SQL 直接报错（推荐）
    dialect: mysql                     # mysql / postgresql / 留空自动

  # ---- 跨服务传输安全 ----
  transport:
    hmac-secret: ${EASY_AT_HMAC_SECRET}  # 生产必填，环境变量注入

  # ---- 恢复 ----
  recovery:
    enabled: true
    interval: 10s
    batch-size: 100
    lease: 30s
    max-retries: 20

  # ---- 管理 API ----
  management:
    enabled: false                     # 开启后暴露 /_easy-at/v1/** 管理端点
    token: ""                          # 鉴权 token，走 X-EasyAt-Token header

  # ---- 按 DataSource 精确控制 ----
  datasource-exclude:                  # 不参与 AT 代理的数据源 Bean 名
    - reportingDataSource
  resources:
    orderDataSource:
      resource-id: order-db            # 覆盖资源 ID（默认用 Bean 名）
      enabled: true
```

## 4. 跨服务示例

### 4.1 前提

两个服务必须共享同一份 **JDBC 或 Redis** 事务存储与锁（`DESIGN.md §9.3`）。File 只做单机验证。

### 4.2 发起方（order-service）

```yaml
easy-at:
  application-name: order-service
  storage: { type: jdbc }
  lock:    { type: jdbc }
  transport: { hmac-secret: ${EASY_AT_HMAC_SECRET} }
```

```java
@Service
public class OrderFacade {

    @EasyAtTransactional(name = "create-order-with-payment")
    @Transactional
    public void createOrder(OrderDTO dto) {
        orderMapper.insert(dto);                     // 本服务写库
        accountClient.decrease(dto.getUserId(), dto.getAmount()); // RestTemplate 调用支付服务
    }
}
```

### 4.3 参与方（account-service）

```yaml
easy-at:
  application-name: account-service
  storage: { type: jdbc }
  lock:    { type: jdbc }
  transport: { hmac-secret: ${EASY_AT_HMAC_SECRET} }
```

```java
@RestController
public class AccountController {

    @PostMapping("/account/decrease")
    public void decrease(@RequestBody DecreaseReq req) {
        // 无需手动处理 XID：AtXidFilter 已从 header 校验签名并绑定 XID，
        // 此处的 DML 会被自动纳入同一个全局事务。
        accountMapper.decrease(req.getUserId(), req.getAmount());
    }
}
```

### 4.4 传播客户端（自动生效，无需手写）

| 客户端 | 机制 | 是否自动 |
|---|---|---|
| `RestTemplate` | `AtRestTemplateInterceptor`（`RestTemplateCustomizer`）| 是 |
| OpenFeign | `EasyAtFeignInterceptor`（`RequestInterceptor`）| 是（需 feign 在 classpath）|
| `WebClient` | `WebClientPropagator`（反射注入 `ExchangeFilterFunction`）| 是（需 webflux 在 classpath）|

发起方抛异常时，`BranchCoordinator` 会逆序通知各参与方回滚；网络失败走幂等重试。

## 5. 管理 API 与运维

开启 `easy-at.management.enabled=true` 并配置 `token` 后：

```bash
# 查单个事务（undo 内容经脱敏）
curl -H "X-EasyAt-Token: your-token" http://localhost:8080/_easy-at/v1/transactions/{xid}

# 查所有待人工介入的事务
curl -H "X-EasyAt-Token: your-token" "http://localhost:8080/_easy-at/v1/transactions?status=MANUAL_INTERVENTION"

# 人工重试 / 强制回滚
curl -X POST -H "X-EasyAt-Token: your-token" http://localhost:8080/_easy-at/v1/transactions/{xid}/retry
curl -X POST -H "X-EasyAt-Token: your-token" http://localhost:8080/_easy-at/v1/transactions/{xid}/rollback
```

浏览器打开 `/_easy-at/v1/ui` 可访问内置只读控制台（同样需 token）。

## 6. 加解密 / 脱敏 SPI

在容器里注册一个 Bean，Starter 会自动装配进 undo 编解码：

```java
@Bean
public UndoDataMasker myMasker() {
    return new ColumnMasker("ssn", "password");   // 这两个列的敏感值在管理输出中会被脱敏
}

@Bean
public UndoDataEncryptor myEncryptor() {
    return new AesUndoDataEncryptor(secretKeySpec); // undo 落库时按列加密
}
```

- 加密：undo 落库时进行，密文以 `{"@t":"enc","@v":base64}` 标签写入 JSON。
- 脱敏：管理 API 诊断输出时进行，明文不出现在日志与管理接口。

## 7. 常见报错排查

| 报错 | 原因 | 解决 |
|---|---|---|
| `UnsupportedAtSqlException` | 用了多表/批量/子查询/无主键/非 `?` 值 | 改成单行、带主键精确条件、占位符传值 |
| `Composite primary keys are not supported` | 表是复合主键 | AT 首版只支持单列主键 |
| `INSERT must explicitly include primary key` | INSERT 没带主键 | 显式传主键值 |
| `requires a Spring local transaction` | `production=true` 且方法无本地事务 | 加 `@Transactional` |
| `Dirty write detected` | 回滚时数据已被并发改动 | 属正常保护，去管理 API 人工处理 |
| `Global lock conflict` | 同一主键被其他事务持有 | 缩短事务、检查锁 `wait-timeout` |

## 8. 生产上线前的必做清单

> 见 [PRODUCTION_GAPS.md](./PRODUCTION_GAPS.md)，此处只列最关键的 5 条。

1. **换真实数据库**：`storage.type=jdbc` + 执行 `easy-at-jdbc/src/main/resources/db/{mysql,postgresql}/easy-at.sql` 建表。
2. **开生产模式**：`production: true` + `transport.hmac-secret`（环境变量注入，勿硬编码）。
3. **补测试证据**：MySQL/PostgreSQL/Redis Testcontainers + 崩溃/并发/故障注入（当前仅 H2 覆盖）。
4. **收紧安全**：source 白名单、密钥轮换、替换 Java 反序列化回退。
5. **压测**：锁等待、恢复扫描、连接池压力。
