# easyAt 项目长期约定

## 构建/验证（关键，务必照做）

- 离线构建：`mvn -o`（本地 m2 无网络，别去掉 -o）。mvn 在 `C:\ProgramData\chocolatey\...\mvn.cmd`。
- **Windows Defender 会锁 `.class` 致编译间歇失败**（报 "cannot access ...X.class"）：
  编译前先 `Add-MpPreference -ExclusionPath 'D:\projectspace\research\easyAt'`，并给 `clean compile` 加 3~5 次重试循环。
- **PowerShell 工具 stdout 不被捕获**：结果用 `Out-File -FilePath x.txt -Encoding ascii` 写文件再 Read。
  **必须 `-Encoding ascii`**，默认 UTF-16 会被 Read 判成二进制、`Get-Content` 读成空。
- 前台 PowerShell 有 ~120s 上限：`clean compile`（~45s）与 `-pl easy-at-jdbc -am test` 分开跑，别合一条。
- Bash 工具损坏（dirname/ls 缺失、safe-bin shim 坏）：用 PowerShell 执行命令。
- 验证基线：`clean compile` 全 7 模块 + jdbc 测试 10 用例（4+3+2+1）应全绿。

## 架构/依赖约束

- `spring-webflux` 不在本地 m2：任何 WebClient/响应式集成必须**反射实现**（见 `WebClientPropagator`），
  禁止加编译期依赖，否则离线编译直接失败。
- 加解密/脱敏 SPI：`JacksonUndoDataCodec(encryptor, masker)`；`UndoDataCodec` 有默认 `toDiagnosticString`。
  Starter 把 `UndoDataEncryptor`/`UndoDataMasker` 自动装配进 `UndoDataCodec` Bean，注入 JDBC/Redis 仓库与 `ManagementService`。
- 管理端点前缀 `/_easy-at/v1`，UI 在 `/_easy-at/v1/ui`（token 走 `X-EasyAt-Token` header）。
- 传输头统一走 `AtTransportHeaders`（XID/DEADLINE/SOURCE/SIGNATURE），不要手写字符串。

## 状态（2026-09-23）

- P0/P1/P2/Redis/加解密脱敏 SPI/WebClient/管理 UI 均已实现；`mvn clean compile` 通过、jdbc 10 用例全绿。
- 剩余仅：生成主键/executeBatch/多表 DML（按设计拒绝）、真实数据库/并发/故障注入测试（需 Docker，沙箱离线不可做）。
