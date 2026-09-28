# 发布到 Maven Central

正式坐标：

```text
io.github.amu10:easy-at-parent:0.1.0
```

示例应用 `easy-at-example-boot3` 已从发布反应器移除（根 pom `<modules>` 不再包含它），不会随父工程发布；需单独构建时用 `mvn -pl easy-at-example-boot3 -am`。其余 7 个库模块随父工程一起发布。

---

## 0. 前置确认（发布前必看）

| 检查项                               | 要求                                          | 当前状态             |
| --------------------------------- | ------------------------------------------- | ---------------- |
| `version`                         | 必须是**非 SNAPSHOT**（Central 拒收 SNAPSHOT）      | ✅ `0.1.0`        |
| `groupId` 命名空间                    | `io.github.amu10` 必须归你所有（GitHub 账号 `amu10`） | ⚠️ 需你在 Portal 验证 |
| 代码状态                              | 已提交、已打 `v0.1.0` 标签、工作区干净                    | 你确认              |
| GPG                               | 本机已装 GnuPG 且有发布密钥                           | ✅ v2.5.24 已装；主钥 `F8906A3147516888` [SC] 已生成并推送 keyserver（待回拉确认） |
| `settings.xml` 的 `central` server | 必须存在且填真实 Token                              | ✅ 已加模板（env 占位）   |

> ⚠️ **正式版本不可覆盖**：`0.1.0` 一旦 Publish 就永久占用，发布前务必确认代码冻结。

---

## 1. 申请 Central Portal 账号 + 验证命名空间

1. 打开 <https://central.sonatype.com> ，点右上角 **Sign in with GitHub**（推荐，直接关联你的 GitHub 身份）。
2. 登录后进入 **Dashboard → Namespaces**（旧版叫 "Publishing" → "Namespace"）。
3. 点 **Add Namespace**，输入 `io.github.amu10`。
4. Portal 会要求证明你拥有 `github.com/amu10`：
   - 选 **GitHub** 验证方式后，按提示在 `github.com/amu10` 下创建一个**公开**仓库（或在该账号已有公开仓库放指定验证文件）。
   - 验证通过后该命名空间状态变为 `Verified`。
5. 进入 **Account → Generate User Token**，得到一对值：
   - `username`：形如一串字符
   - `password`：一长串 token（这是你的私有凭证，**不要提交到 Git**）

把这对值通过环境变量注入（已写入 `~/.m2/settings.xml` 的 `<server id="central">`，用 `${env.*}` 引用）：

```shell
# Windows (PowerShell)
$env:CENTRAL_TOKEN_USERNAME = "你的_username"
$env:CENTRAL_TOKEN_PASSWORD = "你的_password"

# macOS / Linux
export CENTRAL_TOKEN_USERNAME=你的_username
export CENTRAL_TOKEN_PASSWORD=你的_password
```

---

## 2. 安装 GPG 并准备签名密钥

> Maven Central **强制要求**所有构件用 GPG 签名。

**Windows 安装 GnuPG**（本机已用 winget 装好 v2.5.24，位于 `C:\Program Files\GnuPG\bin\gpg.exe`，已写入系统 PATH）：

```shell
# 方式一：winget（已用此方式装好，推荐）
winget install --id GnuPG.GnuPG -e --source winget --accept-package-agreements --accept-source-agreements --silent
# 方式二：Chocolatey（可能撞 NuGet 锁文件，不推荐）
choco install gnupg
# 方式三：下载 Gpg4win 安装包 https://www.gpg4win.org/
```

**生成发布密钥（以 `amu10` 为例，交互式最稳妥）：**

```shell
# 1) 启动交互式生成，跟着提示逐项填
gpg --full-generate-key
```

提示逐项填法（示例值，按你实际情况改）：

| 提示                               | 填什么（示例）                          | 说明                             |
| -------------------------------- | -------------------------------- | ------------------------------ |
| `Please select what kind of key` | `1`                              | 选 `RSA and RSA`                |
| `What keysize do you want?`      | `3072`                           | 不低于 3072                       |
| `Key is valid for?`              | `0`                              | `0`=不过期，或 `2y` 两年              |
| `Real name:`                     | `amu10`                          | 你的发布者名                         |
| `Email address:`                 | `amu10@users.noreply.github.com` | **用你 GitHub 关联邮箱**（也可是常用邮箱，只要公钥能公开验证即可） |
| `Comment:`                       | 直接回车                             | 留空                             |
| `passphrase`                     | 自设强口令                            | **记牢**，部署时要填进 `GPG_PASSPHRASE` |

```shell
# 2) 生成后列出密钥，抓取主钥 ID（取 sec 行 "/" 后的长串）
gpg --list-secret-keys --keyid-format LONG
#   输出示例：
#   sec   rsa3072/F8906A3147516888 2026-09-28 [SC]
#         165DDEBA29AB7F81A1CD89D5F8906A3147516888
#   uid                 [ultimate] amu10 <ltfcg@126.com>
#   ssb   rsa3072/FD816D1CDA32E00A 2026-09-28 [E]
#   说明：sec 是主钥（[SC]=签名+证书），ssb 是加密子钥（[E]）。
#         --send-keys 即使传子钥 ID，gpg 也会解析到主钥并上传整把钥匙。
```

**确认密钥就位：** 上一步能看到形如 `sec rsa3072/F8906A3147516888` 的条目即成功。

**把公钥上传到公共 key server（Central 用它验证签名）：**

```shell
# 用主钥 ID（F8906A3147516888）上传；传子钥 ID 也会被解析成主钥
gpg --keyserver keyserver.ubuntu.com --send-keys F8906A3147516888
# 看到 "gpg: success sending to ..." 即成功
# 顺手也推一份到 keys.openpgp.org 增加冗余：
gpg --keyserver keys.openpgp.org --send-keys F8906A3147516888
```

**验证已发布（在能联网的机器上）：**

```shell
gpg --keyserver keyserver.ubuntu.com --recv-keys F8906A3147516888
# 能拉回即说明已公开，Central 验签能通过
```

**把 GPG 口令交给 Maven。** 已写入 `~/.m2/settings.xml` 的 `<server id="gpg.passphrase">`，用环境变量注入：

```shell
# Windows (PowerShell)
$env:GPG_PASSPHRASE = "你生成密钥时设的 passphrase"
# macOS / Linux
export GPG_PASSPHRASE=你生成密钥时设的passphrase
```

> 也可在部署命令上直接加 `-Dgpg.passphrase=$GPG_PASSPHRASE`，效果相同。
> 本机只有这一把密钥时 maven-gpg-plugin 会自动选中，无需在 pom 里配 `<keyname>`；若以后增加多把密钥，再显式指定 `<keyname>F8906A3147516888</keyname>`。

---

## 3. 本地验证（不签名、不上传）

先确认 `release` profile 能正常产出 sources / javadoc jar：

```shell
mvn clean verify -Prelease -Dgpg.skip=true
```

每个库模块的 `target/` 下应同时存在：主 JAR、`-sources.jar`、`-javadoc.jar`。

> 本机若离线或私服镜像缺失部分插件，这一步可能失败——这是环境限制，不代表配置有误。在你**能联网的本机**上跑即通过。

---

## 4. 上传待审核部署

先确保三个环境变量已设置（见第 1、2 节），然后：

```shell
mvn clean deploy -Prelease
```

- `central-publishing-maven-plugin` 配置为 `autoPublish=false`：命令成功只会上传并校验部署，**不会立即公开**。
- 登录 Central Portal → **Deployments**，检查组件、签名、坐标、依赖。
- 确认无误后点击 **Publish**。`io.github.amu10:easyAt:0.1.0` 即正式进入 Maven Central（通常几分钟内可在 search.maven.org 搜到）。

---

## 5. 常见坑

| 现象                                         | 原因 / 解决                                                                                                         |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------------------- |
| `Cannot find server with id 'central'`     | `settings.xml` 缺 `central` server。已为你加模板，填真实 Token 即可。                                                          |
| GPG 卡在交互输入口令                               | 没给 Maven 口令。设 `GPG_PASSPHRASE` 环境变量（已配 `gpg.passphrase` server）。                                                |
| `gpg: signing failed: Inappropriate ioctl` | Windows 终端无 pinentry。设 `export GPG_TTY=$(tty)` 或用 `-Dgpg.passphrase=` 绕过交互。                                     |
| 上传后 Portal 报命名空间未验证                        | `io.github.amu10` 必须先在 Namespaces 验证通过。                                                                         |
| 发布后想改 `0.1.0`                              | **不行**，正式版不可覆盖。只能发 `0.1.1`。                                                                                     |
| `mirrorOf=central` 指向私服是否影响发布              | 不影响。`central-publishing-maven-plugin` 直连 `api.central.sonatype.com`，只用 `server id=central` 的凭据，不走 Maven 仓库镜像解析。 |
| 部署后在 Portal 报签名无法验证 / key not found       | 公钥没真正上到 keyserver。重跑 `--send-keys` 并确认出现 `gpg: success`；必要时多推几个 keyserver（ubuntu / openpgp.org）。              |
| `Project name is missing`（每个模块都报）           | Central 校验的是**子模块原始 pom**，不读继承值。每个子模块必须自带 `name`/`description`/`url`/`licenses`/`scm`/`developers`。已为 7 个库模块补齐。 |
| `401 Unauthorized` 上传 bundle 失败              | `CENTRAL_TOKEN_*` 环境变量没设或没在跑 mvn 的同一个终端窗口里（环境变量不跨窗口）。去 Portal → Account → Generate User Token 取值重设。       |
