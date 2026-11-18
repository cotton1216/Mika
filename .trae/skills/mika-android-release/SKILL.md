---
name: mika-android-release
description: 一键发布「如意 AI(Mika)」安卓包：按环境（dev/prod）读版本 → 构建 → 签名校验 → ossutil 上传 OSS → apphub 登记并回读。当用户说「发布新版本 / 发版 / 打包发布 Mika / 上传到 apphub / 让客户端能更新 / 本地联调发一版」时使用。**环境必填**，生产与开发两条链路完全隔离。
---

# 如意 AI(Mika) 安卓发布

一条命令完成「构建 → 签名校验 → 传 OSS → apphub 登记 → 回读」。**必须先选环境**，环境一次决定构建类型、签名要求、OSS 落点、默认渠道状态和目标库——不存在「忘记选环境」的默认路径。

## 环境矩阵（核心，先读这张表）

| 维度 | `--env dev` | `--env prod` |
| --- | --- | --- |
| 目标库 | `http://127.0.0.1:3100`（本地 hub，可被 `APPHUB_BASE_URL` 覆盖） | 三级取值：`APPHUB_PROD_BASE_URL` → `--account` 文件 → `~/.config/apphub/agents/*.env` 自动发现 |
| 构建任务 | `:app:assemblePerf -PsideInstall` | `:app:assembleRelease` |
| 包名 | `com.zl.ruyiai.yuan.perf`（并排安装，不动正式包与数据） | `com.zl.ruyiai.yuan` |
| 签名 | 调试签名可用 | **必须** `CN=Yuan AI`，否则直接失败 |
| 产物目录 | `app/build/outputs/apk/perf/` | `app/build/outputs/apk/release/` |
| OSS 前缀 | `ai/ruyi/android/dev` | `ai/ruyi/android` |
| 默认渠道 / 状态 | `beta` / `draft`（入库不对外） | `official` / `published`（对外生效） |
| 账号来源 | `APPHUB_USERNAME` / `APPHUB_PASSWORD` | `APPHUB_PROD_USERNAME` / `APPHUB_PROD_PASSWORD` → `--account` 账号文件 → `APPHUB_*` 回落 |
| 二次确认 | 无 | **必须** `--yes`，否则拒跑 |
| 内网直链 | 允许 | 拒绝（`127.` / `localhost` / `10.` / `192.168.` / `172.16-31.`） |

两套账号之所以用不同变量名，是为了让本地库与生产库的凭证可以同时 `source`，不会互相覆盖。

## 前置条件（缺一不可，先自检）

1. **apphub 发布方账号**（`role=publisher`，应用归属该账号名下）
   - `dev`：账号在**本地库**（`npm run seed:publisher`），建议名 `mika-release`
   - `prod`：账号在**生产库**。本机现成落点是 `~/.config/apphub/agents/<账号>.env`
     （600 权限，内含 `export APPHUB_BASE_URL` / `APPHUB_USERNAME` / `APPHUB_PASSWORD` 三行，
     现有 `agent-01` / `agent-02` / `agent-03`）——用 `--account <账号名>` 直接复用，含其中的生产地址
   - **应用归属先确认**：账号文件只解决「连哪个库 + 用谁登录」，不代表该账号拥有 `com.zl.ruyiai.yuan`。
     用不属于该应用的账号发布会 403；生产首次发布前需由目标账号跑 `app:ensure` 建立归属
   - **建生产账号属生产数据变更，必须先单独和用户确认再执行**
   - 接入细节见 `app-version-hub/skills/apphub-publish/SKILL.md`
2. **ossutil 已装并配好凭证**（本机 v2.4.1，`/Users/binny/.local/bin/ossutil`）
   - 配置文件：`~/.ossutilconfig`（权限 600），格式为 ossutil 2.x 的 `[default]` 段：

     ```ini
     [default]
     accessKeyID=...
     accessKeySecret=...
     region=cn-hangzhou
     endpoint=https://oss-cn-hangzhou.aliyuncs.com
     ```

   - **变量名坑（实测）**：ossutil 2.4.1 只认 `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET`；
     `ALICLOUD_*`、`ALIBABA_CLOUD_*`、以及不少项目自定义的 `ALIYUN_OSS_*` **全部不生效**（会报
     `SigningContext.Credentials is null or empty` 或 `region must be set in sign version 4`）。
     环境变量方案还必须同时给 `--region`（或用配置文件里的 region 行）。
   - 本机凭证来源：`ai-server-desktop/.local/.oss-creds`，里面是 `ALIYUN_OSS_ACCESS_KEY_ID/SECRET`
     （600 权限）。**它不能被 ossutil 直接 `source` 使用**，需转写成上面的配置文件，或映射成
     `OSS_ACCESS_KEY_ID`/`OSS_ACCESS_KEY_SECRET`。
   - 换机器 / CI：用 `OSS_CONFIG_FILE=<路径>`（脚本会转成 `--config-file`），或用上面两个环境变量。
   - RAM 权限：`PutObject`（上传必需）、`GetObject`（公网域名回源读取）、`DeleteObject`（误发包回收）、
     `ListObjects`（排查时列举）。本机现用账号已验证四者可用。
   - dev 也可以完全不碰 OSS：用 `--url` 指向本地静态服务
   - 凭证缺失时脚本在**构建之前**就退出（避免白等一次 gradle 构建），`--dry-run` 会先打印检测结果
3. **gradlew 可构建**（项目根有 `gradlew`；`app/build.gradle.kts` 是版本唯一事实源）
4. **prod 额外要求 `app/release-signing.properties` 存在**（该文件 gitignored，新工作树不会带过来）
   - 缺失时 Gradle 静默回退调试签名，脚本会在构建后用 `apksigner` 拦下

## 版本事实源

版本号**只改一处**：`app/build.gradle.kts` 顶部

```kotlin
val ruyiVersionName = "0.1.50"
val ruyiVersionCode = 51
```

脚本从这两行 + `applicationId` 自动读取，不手动传版本号。`applicationId` 读的是 `defaultConfig` 的值（`com.zl.ruyiai.yuan`），dev 的 `.perf` 后缀只影响安装包名，不改变 appKey。

## 用法

在 Mika 项目根（`/Users/binny/workspace-sdd/mcp-demo/dsh/mika/Mika`）执行。凭证用环境变量注入，不写进命令、不落仓库。

```bash
SCRIPT=.trae/skills/mika-android-release/scripts/release.mjs
```

### 生产发布（对外）

生产地址与账号都从 apphub 的账号文件读，不用手抄地址：

```bash
# 1) 先看动作（必做，不产生任何副作用）
node "$SCRIPT" --env prod --account agent-01 --dry-run --changelog "本次更新说明"

# 2) 确认摘要无误后正式发布（--yes 是 prod 的放行闸门）
node "$SCRIPT" --env prod --account agent-01 --yes --changelog "本次更新说明"
```

地址取值三级：`APPHUB_PROD_BASE_URL` → `--account` 文件里的 `APPHUB_BASE_URL` → 扫 `~/.config/apphub/agents/*.env`（全部一致则直接采用，不一致直接报错要求显式指定）。因此**不带 `--account` 也能拿到地址**，只有账号需要显式给：

```bash
node "$SCRIPT" --env prod --dry-run        # 地址自动发现；账号仍要 --account 或 APPHUB_PROD_USERNAME
```

prod 发布前请逐条确认：版本号已在 `build.gradle.kts` 递增、`release-signing.properties` 在位、changelog 面向用户、该账号确实拥有 `com.zl.ruyiai.yuan`。

### 开发联调（本地库，不对外）

```bash
export APPHUB_USERNAME=mika-release
export APPHUB_PASSWORD='<本地库该账号口令>'
# 本地 hub 未起时先起：cd app-version-hub && npm run dev:server

node "$SCRIPT" --env dev --dry-run                 # 看动作
node "$SCRIPT" --env dev --changelog "本地联调"    # 默认 beta + draft，出不对外
node "$SCRIPT" --env dev --status published        # 想在本地客户端验证「有更新」时可放开
```

dev 出的是 `.perf` 包，与正式包并排安装，可反复覆盖，不影响用户数据。

### 常用变体

```bash
# 已有 APK，跳过构建（注意：包内嵌的检查更新地址由上次构建决定）
node "$SCRIPT" --env prod --skip-build --yes --changelog "…"

# 包已托管在别处 / dev 起个本地静态服务，跳过 OSS 上传
node "$SCRIPT" --env dev --url http://127.0.0.1:8080/mika.apk

# 只发 arm64 小包（默认 universal 全架构，保证所有设备可更新）
node "$SCRIPT" --env prod --abi arm64-v8a --yes --changelog "…"
```

### 可选参数

| 参数 | 默认（dev / prod） | 说明 |
| --- | --- | --- |
| `--env` | **必填** | `dev` 或 `prod`（别名 `development`/`local`/`production`） |
| `--account` | 无 | 读 `~/.config/apphub/agents/<账号名>.env`：提供账号；prod 下同时提供地址，dev 只用账号（地址仍走本地）。`MIKA_ACCOUNT` 等价 |
| `--abi` | `universal` | 可选 `arm64-v8a`（小包，仅 arm64 原生库） |
| `--changelog` | 空 | 给用户看的更新说明；**prod 必填**（空则脚本直接 `fail`，退出码 2），会显示在客户端「关于页 → 更新说明」与公开门户 / 管理台 |
| `--channel` | `beta` / `official` | 渠道 |
| `--status` | `draft` / `published` | `published` 对外生效 |
| `--skip-build` | 关 | 跳过 gradle 构建，用已有 APK |
| `--url` | 无 | 指定下载直链并**跳过 OSS 上传** |
| `--min-supported` | `0` | 低于该 versionCode 的客户端强制更新 |
| `--dry-run` | 关 | 只打印将做的事（prod 必先跑一次） |
| `--yes` | 关 | prod 放行闸门，不加则拒绝执行 |

### 环境变量一览

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `APPHUB_PROD_BASE_URL` | 无（prod 必填） | 生产地址，形如 `https://<PUBLIC_DOMAIN>/apphub` |
| `APPHUB_BASE_URL` | `http://127.0.0.1:3100` | dev 地址；prod 仅作回落并打印告警 |
| `APPHUB_PROD_USERNAME` / `APPHUB_PROD_PASSWORD` | 无 | prod 发布方账号 |
| `APPHUB_USERNAME` / `APPHUB_PASSWORD` | 无 | dev 发布方账号（prod 时可回落） |
| `APPHUB_CLI` | 已知仓库路径 | apphub-publish.mjs 位置 |
| `APKSIGNER` | 自动探测 `ANDROID_HOME/build-tools/*` | prod 签名校验用 |
| `MIKA_EXPECTED_SIGNER_CN` | `Yuan AI` | 期望的签名者 CN |
| `OSS_BUCKET` | `chuangxin-prod` | 桶 |
| `OSS_REGION` | `cn-hangzhou` | 地域 |
| `OSS_ENDPOINT` | `https://oss-cn-hangzhou.aliyuncs.com` | 上传 endpoint |
| `OSS_PREFIX` | `ai/ruyi/android/dev` / `ai/ruyi/android` | 对象键前缀 |
| `OSS_PUBLIC_BASE` | `https://oss-chuangxin.thchengtay.com` | 下载域名 |
| `OSSUTIL` | `ossutil` | ossutil 可执行文件 |
| `OSS_CONFIG_FILE` | 无（默认 `~/.ossutilconfig`） | 非默认位置的 ossutil 配置，脚本转成 `--config-file` |
| `APP_NAME` | `如意 AI` | 应用显示名 |
| `MIKA_ROOT` | 当前目录 | 项目根 |
| `MIKA_RELEASE_ENV` | 无 | 等价于 `--env`，命令行优先 |
| `MIKA_ACCOUNT` | 无 | 等价于 `--account` |

## 直链与对象键约定

- dev：`oss://<bucket>/ai/ruyi/android/dev/ruyi-<versionName>-<abi>.apk`
- prod：`oss://<bucket>/ai/ruyi/android/ruyi-<versionName>-<abi>.apk`
- 登记进 apphub 的直链：`https://oss-chuangxin.thchengtay.com/<对象键>`
- app-version-hub **不托管 APK 二进制**，只登记「元数据 + 直链」；上传由 ossutil 完成
- dev 与 prod 物理分目录，`--force` 覆盖只可能影响同环境同版本号

## 故障排查

| 现象 | 原因 / 处理 |
| --- | --- |
| `必须显式指定环境：--env dev 或 --env prod` | 没传环境。这是刻意的：避免把开发包当正式版发出去。用 `--env dev` 或 `--env prod` |
| `prod 环境必须提供 APPHUB_PROD_BASE_URL` | 生产域名不内置在仓库里。设成 `https://<PUBLIC_DOMAIN>/apphub`（带前缀） |
| `prod 发布会向生产库登记…请追加 --yes` | 闸门生效。先 `--dry-run` 核对，再加 `--yes` |
| `prod 环境的下载直链不能是本机/内网地址` | `--url` 传了 localhost 或内网 IP。改用对外存储，或切 `--env dev` |
| `签名不符：期望 CN=Yuan AI` | `app/release-signing.properties` 缺失 → Gradle 回退调试签名。补齐签名文件后重跑（新工作树常见） |
| `找不到 apksigner` | 设 `APKSIGNER=/path/to/apksigner` 或 `ANDROID_HOME` |
| `缺少 APPHUB_PROD_USERNAME / APPHUB_PROD_PASSWORD` | prod 账号未注入。注意别拿本地库的口令打生产库（两边是两套库两套口令） |
| ossutil `region must be set` / `Invalid signing region` | `OSS_REGION` 与 `OSS_ENDPOINT` 要和桶地域一致（cn-hangzhou） |
| ossutil `region must be set in sign version 4` | 没给 region。环境变量方案必须同时传 `--region cn-hangzhou`，或在 `~/.ossutilconfig` 里写 `region=` 行 |
| ossutil 认证看起来没生效 | 变量名必须是 `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET`。`ALICLOUD_*`、`ALIBABA_CLOUD_*`、`ALIYUN_OSS_*` 一律不生效（实测） |
| `找不到 OSS 凭证：既无 …/.ossutilconfig` | 脚本构建前的前置检查。按「前置条件」第 2 条写入配置文件，或设 `OSS_CONFIG_FILE` |
| ossutil 认证失败 | 未配 `~/.ossutilconfig`，先 `ossutil config` 填 AK/SK |
| `找不到账号文件：…/agents/xxx.env` | 账号名写错。错误信息里会列出可用账号（当前 `agent-01`/`agent-02`/`agent-03`） |
| `账号文件里的 APPHUB_BASE_URL 不一致` | 这些文件连的不是同一个库，说明存在多环境。用 `APPHUB_PROD_BASE_URL` 显式指定，别让它猜 |
| apphub `403 FORBIDDEN` | 已有条目：账号能登录但该 appKey 不属于它 → 先由目标账号跑 `app:ensure` 建立归属，或换有权限的账号 |
| apphub `登录失败 HTTP 401` | 口令错或 token 过期（24h），重跑即重新登录 |
| apphub `403 FORBIDDEN` | 该 appKey 属于另一个账号。确认该由哪个 publisher 账号发 |
| apphub `409 CONFLICT` | 该 versionCode 已存在（上次其实成功了）→ 直接回读确认；要重发先升 `ruyiVersionCode` |
| apphub `404 应用不存在` | 先跑 `app:ensure`（脚本已自动包含这步） |
| 找不到产物 APK | 目录按环境区分：dev 在 `apk/perf/`、prod 在 `apk/release/`；检查 `--abi` 是否选错 |
| gradle 报 `What went wrong: 25.0.4` | PATH 上的 `java` 是 Zulu 25，Gradle 8.11.1 不认。构建前指定 JDK 17 或 21 均可：`export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`，或 `export JAVA_HOME=/Users/binny/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home`（实测 0.1.49/0.1.50 用后者构建通过） |

## 硬规则

- **不传 `--env` 一律拒绝执行**，不提供默认环境
- prod 的域名只走 `APPHUB_PROD_BASE_URL`，脚本与文档都不内置真实域名
- 凭证只走环境变量，不写进命令、不落仓库、不进日志
- 不用 admin 账号做机器发布
- 已发布的 `versionCode` 不回退、不重用；回退旧版＝登记新版本，或把版本 `releaseStatus` 改 `withdrawn`
- 终止操作只有两种：归档应用 / 置 `withdrawn`，**不要删库**
- 创建生产 `mika-release` 账号属生产数据变更，必须先单独确认再执行

## 参考

- 发布 CLI 与认证：`app-version-hub/docs/agent-publishing.md`、`app-version-hub/skills/apphub-publish/SKILL.md`
- 生产部署形态与排障：`app-version-hub/docs/deploy.md`（§6.1 生产机速查、§7.3 网关子路径）
- OSS 上传：`~/.workbuddy/skills/alicloud-storage-oss-ossutil/SKILL.md`
