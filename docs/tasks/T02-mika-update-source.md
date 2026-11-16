# T02 · 更新来源切换到自建发布平台（apphub）

- 状态：doing
- 建档：2026-11-15
- 负责人：实现 AI / 方案决策与验收 用户
- 工作树：`mika/wt-apphub-update`（分支 `feature/apphub-update`，基于 `ruyuan`）

## 目标

如愿安卓端的「检查更新 → 下载 → 自动安装」全部改由自建发布平台 app-version-hub（下称 apphub）驱动，不再依赖 GitHub Releases。

## 范围

本期做：

1. 检查更新改问 `GET {APPHUB_BASE_URL}/api/v1/update/check`，按 `versionCode` 判新旧。
2. 下载直链取自 apphub 登记的 OSS 地址；下载通道、进度、取消逻辑不变。
3. 下载地址白名单由「GitHub 发布附件」改为「OSS 下载域名」。
4. apphub 地址由构建注入：debug 默认 `http://127.0.0.1:3100`，release/perf 必须传 `-PapphubBaseUrl`，缺参构建失败。
5. 发布脚本 `release.mjs` 把同一个 `APPHUB_BASE_URL` 透传给 gradlew，保证「登记的平台」与「客户端问的平台」一致。
6. 缓存文件升级为 `update-v2.json`，避免读到 GitHub 格式的旧缓存。

本期不做：

1. `policy=force` 强制更新拦截（只保留字段与说明，不阻断使用）。
2. 断点续传（`Range`），仍是整包重下。
3. abi 差异化下发（平台表 `uk_versions_scope(app_id, platform, channel, version_code)` 无 abi 维度）。
4. 灰度 `rollout_percent` 客户端计算（平台只存不算）。
5. 平台侧签名/证书下发（App 端继续用本地 sha256 + 包名 + versionCode + 签名四重校验）。

## 方案要点

### 检查更新

- `UpdateChecker(file, currentVersion, currentVersionCode, baseUrl)`：
  - `GET {baseUrl}/api/v1/update/check?appKey=com.zl.ruyiai.yuan&platform=android&channel=official&currentVersionCode=<BuildConfig.VERSION_CODE>`
  - 只带 `User-Agent: Yuan/<versionName>`，不再带 GitHub 的 `Accept` / `X-GitHub-Api-Version` 头。
  - 取 `latest`：`versionName`、`versionCode`、`changelog`、`packageSize`、`sha256`、`publishedAt`、`download.url`。
  - 新旧判定：`latest.versionCode > BuildConfig.VERSION_CODE`；缓存里没有 code 时退回版本号字符串比较。
  - `sha256` 平台给裸 hex，解析时补成 `sha256:<hex>`，对齐 `ApkIntegrity.verify` 的口径。
  - `latest: null`（平台无已发布版本）视为「这次没查到」。
- 保留 2 分钟自动检查节流、单飞锁、磁盘缓存（`update-v2.json`）。

### 下载

- `download.url` 必须是 HTTPS、主机在白名单内、路径以 `.apk` 结尾。
- 白名单按**主机**：`oss-chuangxin.thchengtay.com`（过渡期保留 `github.com`）。按路径前缀放行会在换桶/换域名时把所有老客户端锁死，真正的防篡改靠下载后的 sha256 / 包名 / versionCode / 签名校验。
- `DownloadManager`（非代理）与 `ProxiedApkDownload`（代理）两条通道、`.part` 临时文件、64KB 缓冲与进度回调均不变。

### 安装

- 安装链路零改动：`FileProvider` + `ACTION_VIEW` + 系统安装器，`REQUEST_INSTALL_PACKAGES` 授权跳转不变。
- 校验顺序不变：大小 → sha256 → 包名 → versionCode 递增 → 签名（含轮换历史）。

### 构建注入

- `app/build.gradle.kts` 在 `androidComponents.onVariants` 内按变体写 `buildConfigField APPHUB_BASE_URL`：
  - 传了 `-PapphubBaseUrl` → 用它（去掉尾部 `/`）。
  - 未传且变体是 debug → `http://127.0.0.1:3100`。
  - 未传且本次构建要出 release/perf → 配置阶段直接失败。
- 生产地址形如 `https://<域名>/apphub`（网关剥 `/apphub` 前缀）。

## 验收标准

1. `./gradlew :app:testDebugUnitTest` 通过（AppHub 解析、UpdateChecker、存盘往返）。
2. `./gradlew :app:assembleDebug` 通过，`BuildConfig.APPHUB_BASE_URL == "http://127.0.0.1:3100"`。
3. `./gradlew :app:assembleRelease` 不传 `-PapphubBaseUrl` 失败；传了通过且 `BuildConfig.APPHUB_BASE_URL` 等于所传值。
4. 本地 apphub 登记一个更高 versionCode 的版本后，App 手动检查更新能出红点与更新说明。
5. 下载后校验通过并拉起系统安装器；改坏 sha256 或换成非白名单主机地址时被拒。

## 阻塞与依赖

- 生产域名未定：`APPHUB_BASE_URL` 由发版时注入，代码不写死。
- 生产 publisher 账号（`mika-release`）与 OSS AK/SK 未配置（T01 遗留），不阻塞本任务编译与本地联调。
- 真发版需递增 `ruyiVersionCode`（49 已占用）。

## 影响评估

- 影响文件：`MikaLinks.kt`、`Releases.kt`、`AppHub.kt`（新）、`UpdateChecker.kt`、`ApkIntegrity.kt`、`MikaApp.kt`、`app/build.gradle.kts`、`release.mjs`、`AppHubTest.kt`（新）、`ReleasesTest.kt`、`UpdateCheckerTest.kt`、`ApkIntegrityTest.kt`。
- 行为变化：检查更新只问 apphub；GitHub 格式的旧缓存不再读取；平台无已发布版本时提示「没查到」。
- 兼容：已装老版本客户端仍走 GitHub 逻辑，不受影响；新包要求 apphub 已登记同 code 或更高版本，否则用户查不到更新。
- 风险：release 构建漏传 `-PapphubBaseUrl` 会硬失败（有意）；若用 `./gradlew build` 之类非发版任务编译 release，会注入本地地址，发版只走 `release.mjs`。

## 回滚方案

- 触发：新包上线后检查更新/下载/安装任一环节大面积失败且当日无法修复。
- 动作：`git revert` 本任务提交，递增 `ruyiVersionCode` 后重新出包并登记 apphub（老客户端会按更高 versionCode 收到回滚包）；不保留编译开关。
- 注意：一旦发版，客户端已内置 apphub 地址，回滚包也必须由 apphub 下发。

## 进度快照

- 已完成：
  - 代码：`MikaLinks`（`RELEASES` 改读 `BuildConfig.APPHUB_BASE_URL`、新增 `checkUrl`）、`AppHub`（解析 `latest`/`download`/`policy`，裸 sha256 补前缀）、`Releases`（缓存结构补 `versionCode`/`force`）、`UpdateChecker`（按 versionCode 判新旧、只带 User-Agent、缓存 `update-v2.json`）、`ApkIntegrity`（按主机白名单：`oss-chuangxin.thchengtay.com` + 过渡 `github.com`，仍要求 HTTPS/443/`.apk`）、`MikaApp`（传 `BuildConfig.VERSION_CODE`）、`app/build.gradle.kts`（onVariants 注入 `APPHUB_BASE_URL`，release/perf 缺参配置阶段失败）、`release.mjs`（透传 `-PapphubBaseUrl`）。
  - 测试：新增 `AppHubTest`；重写 `ReleasesTest`、`UpdateCheckerTest`、`ApkIntegrityTest`。
- 已验证（2026-11-15，JDK 21.0.5-tem，Android SDK `~/Library/Android/sdk`）：
  - `./gradlew :app:testDebugUnitTest` 通过（52 个用例，0 失败）。
  - `./gradlew :app:assembleDebug` 通过，生成的 `BuildConfig.APPHUB_BASE_URL = "http://127.0.0.1:3100"`。
  - `./gradlew :app:assembleRelease`（不传参）在配置阶段失败，提示「出 release/perf 包必须传 -PapphubBaseUrl」。
  - `./gradlew :app:assembleRelease -PapphubBaseUrl=https://example.com/apphub/` 通过，生成 `APPHUB_BASE_URL = "https://example.com/apphub"`（尾部 `/` 已去掉），`:app:lintVitalRelease` 通过。
  - `./gradlew :app:assemblePerf`（不传参）同样在配置阶段失败。
- 基点同步（2026-11-15，用户确认执行）：本分支原基于 `530a7f8b`，ruyuan 已前进至 `5819d70d`；先 `git stash push -u` 暂存 15 处改动，`git rebase ruyuan` 快进（本分支无自有提交），`git stash pop` 无冲突恢复。同步后重跑 `:app:testDebugUnitTest :app:assembleDebug`：BUILD SUCCESSFUL，单测 2170 项 0 失败，debug 包构建通过。
- 最新判定语义修复 + 全语言更新文案（2026-11-15，操作人：AI；原代码负责人：未确认；提交日期：2026-11-15；Commit ID：`6d7252db`）：
  - 问题：`latest:null`（平台无已发布版本）被 `UpdateChecker` 判成失败，界面显示「连不上 GitHub」。
  - 修复：`AppHub.parse` 改为「`latest` 缺失/为 null 返回 null；响应读不懂抛异常」；`UpdateChecker.fetchLatest(): Result<ReleaseInfo?>`，`check()` 以 `result.isSuccess` 判 Done/Failed，成功时用平台答案覆盖旧 latest（清旧红点）。
  - 文案：11 个语言目录的 `about_check_sub`、`about_offline`、`about_offline_toast`、`about_changelog_all`、`about_update_download` 及 `about_data_note` 句尾从 GitHub 口径改为发布平台口径；`about_feedback*`、`about_repo*` 仍指向 GitHub。
  - 验证：`:app:testDebugUnitTest`（含新增 `brokenResponseThrows`、`platformWithoutPublishedVersionIsNotAFailure`、`unreadableBodyIsAFailure`）与 `:app:assemblePerf -PsideInstall -PapphubBaseUrl=http://10.0.2.2:3100` 均 BUILD SUCCESSFUL。
  - 模拟器 `dshgo_android` 复测：新 perf 包（`com.zl.ruyiai.yuan.perf`，versionCode 49，dex 含 `http://10.0.2.2:3100`）覆盖安装后登录态保留；点「检查更新」出现 `10.0.2.15:xxxxx → 10.0.2.2:3100` 的 ESTAB 连接，本地接口返回 `hasUpdate:false, latest:null`，界面副标题保持「从发布平台获取新版本」（不再是「检查更新失败」），即判定为 Done。
- 未验证：
  - 验收标准 4、5（本地 apphub 登记更高 versionCode 后的红点、下载、SHA/包名/versionCode/签名校验、拉起系统安装器）未做——需往本地 apphub 写服务数据，待单独确认。
  - 真机未使用（按要求）。
- 测试编译修复（2026-11-15，操作人：AI；原代码负责人：未确认；提交日期：2026-11-15；Commit ID：`6d7252db`）：
  - 移除 `AboutScreenTest.kt` 对已不存在的 `onSaveQr` 参数的调用；`:app:compileDebugAndroidTestKotlin` 已通过。
  - 旧页面断言仍保留，未经真机验证：`showsHeroRowsNoteAndFooter`、`externalRowsOpenLinks` 和 `groupRowOpensGroupSheet` 引用当前页面已无的入口；二维码保存回调断言也已不再有调用来源。
- 环境备注：
  - 本机默认 JDK 25 不被 Gradle 8.11.1/AGP 支持，验证统一用 `JAVA_HOME=~/.sdkman/candidates/java/21.0.5-tem`。
  - 新工作树缺少 `local.properties`，已补 `sdk.dir=/Users/binny/Library/Android/sdk`（gitignore 忽略，不入库）。
- 未验证假设已闭合：AGP 8 的 `variant.buildConfigFields` 可写入未在 DSL 声明的 `APPHUB_BASE_URL`，debug/release 均已编译验证。
- 后台下载 + 进度展示 + 通知栏（2026-11-15，操作人：AI；原代码负责人：未确认；提交日期：2026-11-15；Commit ID：`1aea07d8`；用户授权档位：A 全套）：
  - 需求：页面停留「正在下载…」且无百分比/无通知栏进度；要求后台继续下载、页面与通知栏都显示进度，完成后点通知回应用、经校验再进系统安装器；直连（DownloadManager）与应用内（代理/Wi-Fi）两条路径都要满足。
  - 改动：`ApkUpdater` 增加 `DownloadPhase.Pending` 与 `ApkDownloadState(bytes/total/percent)`、`progress(context)`；`ApkDownloadForeground`（新，前台服务 `dataSync`，500ms 轮询刷新通知进度，Ready 后发完成通知并自停）+ `UpdateNotifications`（渠道 `app_update`，进度标题/百分比/已下总量、完成通知点击回 Open）；`ApkDownloadReceiver`（新，DownloadManager 完成后补发完成通知）；`AutomaticUpdates` 识别 Pending/Downloading/Paused 继续等待；`UpdateDownloadControls` 增加进度条 + 「xx% · 已下 / 总量」+ 等待态文案；11 个语言目录补 `about_update_waiting`、`notif_channel_update`。
  - 验证（模拟器 `emulator-5554`，8 核/3MB→0.8MB/s 限速静态服务，perf 包 versionCode 49 → 50）：
    - 页面：弹窗按钮「正在下载 37%」，下方进度条 + 「37% · 46 MB / 120 MB」。
    - 通知栏：`app_update` 渠道常驻通知，标题「正在下载…」，正文「42% · 51 MB / 120 MB」，带进度条；切到桌面 12 秒内文件 61 MB → 71 MB，后台继续下载。
    - 完成：服务自停、通知变「安装更新 / 0.1.49」；点通知直接打开更新弹窗（不再绕过校验）；点「安装更新」→ sha256/大小/包名/versionCode/签名校验通过 → `FileProvider` 拉起系统安装器「要更新此应用吗？如愿 测试版」→ 选「更新」后 `versionCode=50 / versionName=0.1.49`。
    - 装后：关于页显示「版本 0.1.49」「已是最新」，自动更新开关保持开启；`Download/updates` 目录已清空（`dropInstalled` 生效）。
  - 环境限制（非代码问题）：模拟器内 `com.android.providers.downloads`（DownloadManager）连 `10.0.2.2:8099` 一律 20s 超时（`HTTP_DATA_ERROR` 每 20s 重试），同机 `toybox nc`、NetworkMonitor、App 进程均正常；因此直连路径只验证到「Pending → 等待网络连接…」文案，真机/公网 HTTPS 需另测。
  - 额外操作（模拟器侧，可回退）：`pm grant ... POST_NOTIFICATIONS`；`appops set ... REQUEST_INSTALL_PACKAGES allow`；`cmd wifi connect-network AndroidWifi open`；`settings put global captive_portal_*` 指向 `http://10.0.2.2:8099/generate_204`。
  - 通知权限（2026-11-15，提交日期：2026-11-15；Commit ID：`1aea07d8`）：原「待确认」已闭环——`UpdateDownloadControls` 在点「下载更新」前，Android 13+ 且未授予 `POST_NOTIFICATIONS` 时先发起运行时申请；被拒时静默跳过（不阻断下载），模拟器权限仍由 `pm grant` 预置。`:app:assemblePerf` 与 `:app:testPerfUnitTest` 均 BUILD SUCCESSFUL，权限弹窗真实交互未在模拟器复测。

## 提交记录

| 顺序 | Commit ID | 类型 | 内容摘要 | 提交日期 |
| --- | --- | --- | --- | --- |
| 1 | `6d7252db` | 更新源切换 | 检查更新改问 apphub、按 versionCode 判新旧、OSS 域名白名单、`APPHUB_BASE_URL` 构建注入与 `release.mjs` 透传、11 语言文案改口径、`AboutScreenTest` 编译修复、本任务文档 | 2026-11-15 |
| 2 | `1aea07d8` | 后台下载进度 | `DownloadPhase.Pending`、前台服务与通知栏进度、`ApkDownloadReceiver` 完成通知、关于页进度条与等待态文案、下载前申请 `POST_NOTIFICATIONS` | 2026-11-15 |
| 3 | 本提交 | 文档回填 | 回填上述两个 Commit ID 与提交流水（仅文档，不改代码） | 2026-11-15 |

- 拆分口径：11 个 `strings_about.xml` 按内容拆开——文案口径改动归提交 1，新增的 `about_update_waiting`、`notif_channel_update` 归提交 2；两个提交的完整树已验证（`assemblePerf` + `testPerfUnitTest` 通过），中间提交未单独编译。
