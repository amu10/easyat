# easyAt Spring Boot 3 示例（多模块）

本目录是 `easyAt` 的示例聚合工程，拆成 **3 个独立可运行模块**，各自是一个单独的 Spring Boot 应用：

| 模块 | 端口 | 角色 | 说明 |
| --- | --- | --- | --- |
| `easy-at-example-local` | 18080 | 单服务本地 demo | 同库两账户转账，`@EasyAtTransactional` 在单进程内提交/回滚。无 Feign、无 Nacos。 |
| `easy-at-example-remote-caller` | 18181 | 下单服务（发起方） | `@EasyAtTransactional` 开启全局事务 → 扣账户+写订单 → 经 OpenFeign 调 callee 扣库存。 |
| `easy-at-example-remote-callee` | 18182 | 库存服务（参与方） | 从 Feign 请求头 `join` 进同一 XID，本地 `@Transactional` 的 undo 自动挂到该全局事务下。 |

> 三个模块共用同一个 `easyat01` 库的 `easy_at_*` 协调表（undo 按 XID 聚合，caller 回滚时统一补偿双方数据）。
> `easy-at-example-boot3` 本身只是聚合 pom（packaging=pom），不参与运行。

## AT 模式受限 SQL（重要，写业务代码前先读）

undo 日志生成器采用**保守策略**：接受按主键的 INSERT/UPDATE/DELETE，以及默认最多 100 行的
`WHERE 主键 IN (?,...)` UPDATE/DELETE。UPDATE 支持参数、标量字面量、函数白名单和同列算术。
不符合的语句会在执行时抛 `UnsupportedAtSqlException`——这是刻意的设计
（不为无法确定的 SQL 生成「猜测性」undo），不是 bug。

| 语句 | 要求 |
| --- | --- |
| UPDATE | 支持参数、标量字面量、函数白名单和同列算术；支持 `WHERE 主键=?` 或 `WHERE 主键 IN (?,...)` |
| DELETE | 支持 `WHERE 主键=?` 或 `WHERE 主键 IN (?,...)` |
| INSERT | 必须**显式写出主键列**，且所有值都是 `?`（undo 是按主键 DELETE） |
| 共同 | 表必须有**单列主键**；JDBC Batch 可用；不支持跨列计算、JOIN / 子查询 / 多表 / 表别名 / 未列入白名单的函数 |

```java
// ✅ 支持：目标列自身参与的算术组合
jdbc.update("UPDATE account SET balance=balance-? WHERE id=?", amount, userId);
jdbc.update("UPDATE account SET balance=balance*?+? WHERE id=?", rate, bonus, userId);
// ✅ 支持：标量字面量不占 JDBC 参数位置
jdbc.update("UPDATE orders SET status='PAID' WHERE id=?", orderId);

// ❌ 不支持：跨列计算，无法按当前保守规则验证表达式语义
jdbc.update("UPDATE account SET balance=credit-? WHERE id=?", amount, userId);
```

> 无论正向 UPDATE 是直接赋值、字面量还是同列算术，Undo 都直接把
> before image 中的旧值写回，不会通过反向计算猜测补偿结果。

## 准备数据库

示例默认连 `localhost:3306/easyat01`，账号/密码 `root/root`。创建库即可，表由启动时 `schema.sql` 自动建（幂等）：

```sql
CREATE DATABASE IF NOT EXISTS easyat01
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

## 构建（安装依赖到本地仓库）

easyAt 主工程与 starter 需先装进本地仓库（示例依赖它们）。在 **easyAt 根目录**执行一次：

```shell
mvn install -DskipTests
```

然后进入本示例聚合目录，安装示例模块（含聚合 parent pom）：

```shell
cd easy-at-example-boot3
mvn install -DskipTests
```

## 运行

三个模块分别启动，每个都是独立进程。可以在三个终端里分别跑：

```shell
# 终端 1：本地转账 demo（端口 18080）
cd easy-at-example-boot3/easy-at-example-local
mvn spring-boot:run

# 终端 2：下单服务 caller（端口 18181）
cd easy-at-example-boot3/easy-at-example-remote-caller
mvn spring-boot:run

# 终端 3：库存服务 callee（端口 18182）
cd easy-at-example-boot3/easy-at-example-remote-callee
mvn spring-boot:run
```

> 远程示例需要 Nacos 可访问 `192.168.110.237:8848`（仅服务发现，无需配置中心）。
> caller 通过 `@FeignClient("inventory-service")` 按服务名发现 callee，两个进程都会在 Nacos 注册。
>
> Nacos 地址必须写在 `spring.cloud.nacos.discovery.server-addr` 上；只写 `spring.cloud.nacos.server-addr`
> 会让 `NacosDiscoveryProperties.serverAddr` 为 null，注册时报
> `Client not connected, current status:STARTING` 并导致启动失败。
> （示例里这两行默认注释着，若启动报上面的错就放开。）
>
> `discovery.ip: 127.0.0.1` 用于多网卡机器强制注册成本机回环地址，避免 caller 拿到不可达的 IP。

---

## 示例一：本地转账（local，18080）

初始余额：

```shell
curl http://localhost:18080/demo/accounts
```

成功转账 100：

```shell
curl -X POST "http://localhost:18080/demo/transfer?from=1&to=2&amount=100"
```

失败回滚（扣款后模拟异常，整笔回滚）：

```shell
curl -X POST "http://localhost:18080/demo/transfer?from=1&to=2&amount=100&fail=true"
```

观察全局事务与 undo 日志：

```shell
curl http://localhost:18080/demo/transactions
curl http://localhost:18080/demo/undo-logs
```

---

## 示例二：跨服务远程事务（caller 18181 + callee 18182）

### 成功（一并提交）

```shell
curl -X POST "http://localhost:18181/order/place?userId=1&itemId=1001&qty=2"
```

- 账户 1 余额减少（`qty * 10 = 20`）；库存 1001 减少 2（剩 98）；订单状态变为 `PAID`。
- 全局事务状态 `COMMITTED`。

### 失败（一并回滚）

```shell
# 让库存服务抛异常（Feign 转异常抛回 caller，触发整笔回滚）
curl -X POST "http://localhost:18181/order/place?userId=1&itemId=1001&qty=2&fail=true"
# 或直接超出库存：
curl -X POST "http://localhost:18181/order/place?userId=1&itemId=1001&qty=999"
```

- 虽然 caller 已扣账户、写订单，callee 已扣库存，但异常导致全局事务回滚：
  **账户余额、订单、库存全部恢复**到事务开始前。全局事务状态 `ROLLED_BACK`。

### 观察（在 caller 18181 上，一次看全）

```shell
curl http://localhost:18181/order/state
```

返回 `accounts` / `inventory` / `transactions` / `undoLogs` 四块。

> `/demo/accounts`、`/demo/transactions`、`/demo/undo-logs` 属于 **local(18080)** 模块，caller 上没有；
> caller 只有 `POST /order/place` 与 `GET /order/state`。

---

## 如何验证 XID 是否通过 OpenFeign 传过去了

传播链路：`EasyAtFeignInterceptor`（caller 侧 Feign 拦截器）写请求头 → `AtXidFilter`（callee 侧
Servlet Filter）读 `X-EasyAt-Xid` 并 `manager.join(xid)`。四个头定义在
`io.github.easyat.core.AtTransportHeaders`：

| 请求头 | 含义 |
| --- | --- |
| `X-EasyAt-Xid` | 全局事务 ID（**核心**） |
| `X-EasyAt-Deadline` | epoch 毫秒，超时即拒（防重放），默认 +30s |
| `X-EasyAt-Source` | 调用方应用名 |
| `X-EasyAt-Signature` | HMAC 签名，仅 `production=true` 且配了密钥时才写 |

### 方式 1：看 caller 实际发出的请求头（最直接）

caller 已开启 Feign `loggerLevel: FULL` + 该 Feign 接口的 DEBUG 日志。发起下单后，caller 控制台会打印：

```
[InventoryFeignClient#deduct] ---> POST http://inventory-service/inventory/deduct?... HTTP/1.1
[InventoryFeignClient#deduct] X-EasyAt-Xid: 6f1c8e0a-...
[InventoryFeignClient#deduct] X-EasyAt-Deadline: 1770000000000
[InventoryFeignClient#deduct] X-EasyAt-Source: order-service
[InventoryFeignClient#deduct] ---> END HTTP (0-byte body)
```

> 前提：`AtContext.xid() != null`（确实在 `@EasyAtTransactional` 里）时拦截器才写头。
> 没有事务时不会有任何 `X-EasyAt-*` 头——这也是判断"事务开没开"的旁证。

### 方式 2：看两端日志里的 xid 是否一致

caller 与 callee 的 Service 里都加了观测日志：

```
[AT-caller] 全局事务已开启，xid=6f1c8e0a-...
[AT-caller] 准备调用 inventory-service，将传出 xid=6f1c8e0a-...
[AT-callee] 收到扣库存请求，joined xid=6f1c8e0a-... (inAT=true)
```

**两边 xid 一致 = 传播成功**。若 callee 打印 `xid=null` / `inAT=false`，说明请求头没到——
通常是 `EasyAtFeignInterceptor` 没注册（Feign 不在 classpath）或 caller 侧事务没开。

### 方式 3：查 undo 日志表（最硬的端到端证据）

caller 与 callee 共享同一个 `easyat01` 库的 `easy_at_undo_log`。XID 传过去且 join 成功后，
**同一个 xid 下会出现两个服务各自写的 undo**（`resource_id`、`table_name` 不同）：

```sql
SELECT xid, resource_id, table_name, pk_value, status, created_at
FROM easy_at_undo_log
ORDER BY created_at DESC LIMIT 20;
```

期望看到同一个 `xid` 下既有 `account` / `orders`（caller 写的），也有 `inventory`（callee 写的）。
直接 `curl http://localhost:18181/order/state` 里的 `undoLogs` 就是这个查询结果。

### 方式 4：绕过 caller，手动带 header 打 callee（隔离验证 filter）

```shell
curl -X POST "http://localhost:18182/inventory/deduct?itemId=1001&qty=1&fail=false" \
  -H "X-EasyAt-Xid: not-a-real-xid"
```

若返回 xid 不存在的错误（如 `Transaction not found`），恰好证明 `AtXidFilter` **读到了这个头并尝试 join**；
若毫无反应、直接扣减成功，说明 filter 根本没生效（bean 没注册或被 security 拦截成 403）。

---

## 关键传播链路

```
caller @EasyAtTransactional 开启全局事务，绑定 XID
  → 调用 Feign 时，EasyAtFeignInterceptor 把 XID 注入请求头
  → callee 的 AtXidFilter 取出 XID 并 manager.join(xid) 加入同一事务
  → callee 的 @Transactional 本地 DML 产生的 undo 挂到该 XID 下
  → 任一方抛异常，caller 回滚时按 XID 聚合补偿 caller + callee 的全部 undo
```

> 生产项目应使用 JDBC 或 Redis 作为共享存储和锁，并启用 `easy-at.production=true` 与 HMAC 密钥。
> 本示例仅用于本地体验：本地 MySQL、未启用 HMAC 签名、默认不清历史。
>
> 依赖版本：`spring-cloud.version=2023.0.3`、`spring-cloud-alibaba.version=2023.0.3.2`（与 Spring Boot 3.3.3 匹配）。
> jackson 由 `spring-boot-dependencies` 统一锁定为 `2.17.2`——nacos-client 2.4.x 需要 jackson-core ≥ 2.15，
> 否则启动时会抛 `NoClassDefFoundError: com.fasterxml.jackson.core.exc.StreamConstraintsException`。
> 若你的环境依赖解析失败，按本地 Spring Boot 版本调整 `easy-at-example-boot3/pom.xml` 里的这几个属性即可。
