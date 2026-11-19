# T09 · APK 更新事实源从 apphub 接口切到自研静态 feed

## 目标

把如意安卓端的更新事实源从 apphub 动态接口 `/api/v1/update/check` 切到自研静态 feed `latest.json`；apphub 退回纯台账登记；与 desktop「仅台账旁路」对齐。

## 范围

- 本期做：feed 生成器 + 上传脚本；客户端 `AppHub` 改静态解析；`MikaLinks`/`UpdateChecker`/`build.gradle.kts`/`release.mjs` 去 apphub 消费；单测与 UI 测试迁移。
- 本期不做：desktop 改动；apphub 服务端；APK UI 大改；11 语言文案口径统一（除非必要）。

## 方案要点

- feed 托管：`https://ai.thchengtay.com/desktop/xxt/ruyi/android/latest.json`（desktop 同域、web 根 `/www/wwwroot/xxt-desktop/desktop/xxt/` 下新建 `ruyi/android/` 子目录）。注意：nginx 只对 `/desktop/xxt/` 前缀开放对外访问；早期设想的根路径 `/ruyi/android/latest.json` 公网实测 404（探针验证），已弃用。
- feed 格式（JSON）：`version` / `versionCode` / `publishedAt` / `changelog` / `download{url,size,sha256}`。
- 版本判定：`versionCode` 优先，缓存无 code 时退回版本号比较（沿用现状）。
- 强制更新：`force` 字段保留但不拦截（沿用 `Releases.kt` 现状）。
- 客户端读 feed 无域名白名单；APK 下载白名单不变（仍 OSS）。

### 过渡期双写（关键，勿删 apphub publish）

存量用户手机上的**旧客户端**内嵌 `BuildConfig.APPHUB_BASE_URL`，只请求 `/api/v1/update/check`（channel=official，按 versionCode 判新旧），**不认识新 feed**。因此切换 feed 后不能停 apphub 登记：

- 引导链：旧客户端 → apphub 查到新版 → 下载安装新 APK（读 feed 的版本）→ 此后改走 feed。
- 发版链必须**双写**：apphub `app:ensure`+`publish`（引导存量用户）与 feed `generate`（服务已升级用户），用同一 `versionCode`、同一 OSS 直链。
- 只发 feed 不登记 apphub = 存量用户永远停在旧版本；只登记 apphub 不发 feed = 老用户升级后反而收不到后续更新。
- `release.mjs` 的 apphub 登记步骤加了桥接语义注释；`SKILL.md` 开头与硬规则同步此约束。
- **桥接后简化**：`release.mjs` 新增 `--skip-apphub` 开关，跳过 `app:ensure`+`publish` 及凭证/地址前置检查（prod 无账号文件也能跑），只构建/签名/传 OSS/发 feed；prod `--yes` 闸门仍生效。桥接完成、确认存量老客户端已升级后用这个模式做常规发版。
- 退出条件：确认活跃老客户端占比极低（或做强制升级）后，改用 `--skip-apphub` 发版。

## 验收标准

1. APK 从静态 feed 拿到版本/直链/sha256/changelog。
2. 红点 / changelog / 下载校验 / 自动更新 / 2 分钟节流全部保留。
3. apphub 台账登记不变。
4. 单测与 UI 测试迁移后全绿。

## 阻塞/依赖

- feed 上传需 SSH 到 desktop web 根 + `mv` 原子切换；生产写入需用户单独授权。
- 服务端 `desktop/xxt/ruyi/android/` 目录需存在（feed 上传脚本会 `mkdir -p` 自动创建）。

## 影响评估

- 触及客户端更新核心链路，约 20+ 文件；风险中高，需真机回归。

## 回滚方案

- 保留 apphub 登记与旧解析逻辑可回切；feed 发布位可整目录回退。

## 实施记录（2026-11-16）

实施层已完成，本地验证通过；**任务保持 doing**——未提交、未真机回归、未生产发布。

## 发布记录（2026-11-17 01:15，桥接版）

- **prod v0.1.51 / code 52 已正式发布，全流程退出码 0**。命令：`release.mjs --env prod --account agent-01 --yes --changelog "优化应用更新机制，提升更新稳定性与速度。"`（桥接版双写，不带 `--skip-apphub`）。
- 构建签名 CN=Yuan AI OK（APK 132559061 B，sha256 `e55e7956…ec70`）；OSS `ai/ruyi/android/ruyi-0.1.51-universal.apk` 上传成功。
- apphub `publish` id=6（official/published，code 52），回读 `hasUpdate=true latest=0.1.51`——存量老客户端引导链已通。
- 静态 feed scp→stage sha256 校验→原子 mv→HTTP 回读一致；feed URL `https://ai.thchengtay.com/desktop/xxt/ruyi/android/latest.json`。
- **路径修正**：原 `/ruyi/android/latest.json` 公网 404（nginx 只 serve `/desktop/xxt/`），用户拍板改用 `/desktop/xxt/` 路径；已同步代码/文档默认值。
- 发布后 curl 独立验收：feed JSON / apphub update/check / OSS Range 206 三处全部正确。
- 待办：commit/push（显式路径 add）；真机回归；桥接完成后常规发版用 `--skip-apphub`。

- 客户端：`AppHub` 静态 feed 解析（空 `{}`→null、坏 feed 抛异常、裸 hex 补 `sha256:`、versionCode 优先）；`UpdateChecker` 改 `feedUrl` 直读；`MikaLinks` 删 apphub 常量、加 `UPDATE_FEED`；`ApkIntegrity` 白名单改为对比 feed host；`build.gradle.kts` 用 `updateFeedUrl` 注入 `BuildConfig.UPDATE_FEED_URL`，release/perf 强制传参。src 全量 grep 已无 apphub 更新接口残留。
- 发版链：`release.mjs` 构建参数改 `-PupdateFeedUrl`，apphub `app:ensure`+`publish` 保留为纯台账，末尾调用 `feed-publish.mjs generate`；新增 `--feed-url`/`--feed-skip-upload` 与 `UPDATE_FEED_URL`/`FEED_*` 环境变量。
- 新增 `feed-publish.mjs`：`generate`/`validate`/`rollback`/`show` 四子命令；本地 APK 现算 size/sha256；stage 临时文件 + 原子 `mv`；上传前备份 `latest.json.bak`，失败自动回滚；HTTP 回读校验；ssh/scp 走参数数组 + 路径白名单（沿用 desktop `manifest-switch.mjs` 安全模型）。
- 验证：about 包 54 单测全绿；`assembleDebug` 编译通过；release 强制传参守卫反证生效；`release.mjs --env prod --dry-run` 串接正常；feed-publish 各子命令本地实测通过。
- 待办：生产 feed 上传（改线上更新源，需单独授权）；真机回归（红点/changelog/下载校验/自动更新/节流）；commit/push（显式路径 add，禁 `-A`/`-a`）。

## 收口记录（2026-11-19）

**结论：代码与发布已收口，真机回归未做**（验收标准 1、2 无设备证据，只有单测覆盖）。

- **提交**：`a36a0f66` feat(update): 更新事实源改读自研静态 feed（T09 实施层），15 文件 +855/−173；同补 `.gitignore` 忽略 `.local/`。
- **合并**：`9786c57f` 合入 `ruyuan`，之后主干快进到该提交。5 处冲突处理——版本取主干 `0.1.52/53`（工作树的 `0.1.51/52` 会让已装 0.1.52 的用户被判降级）；`TASKS.md` 保留 T08/T09/T10 三行；`MikaLinks.kt`、`release.mjs`、`SKILL.md` 取品牌措辞 + feed 流水线口径。
- **验证**：`:app:assembleDebug` 通过；`:app:testDebugUnitTest` 2179 项 / 0 失败 / 0 跳过；`app/src` 与 `core/src` 已无 `api/v1/update/check`、`APPHUB_BASE_URL` 残留。
- **feed 补发（关键）**：0.1.52 那次发版从 `ruyuan` 走，链路里没有 feed，只写了 apphub——导致已升级到 0.1.51 的客户端（只读 feed）看不到 0.1.52。本次用本地同源 APK 补发：

  | 项 | 值 |
  | --- | --- |
  | feed | `https://ai.thchengtay.com/desktop/xxt/ruyi/android/latest.json` |
  | 版本 | `0.1.52` / code 53（原 `0.1.51` / 52） |
  | 直链 | `ruyi-0.1.52-universal.apk`，132,788,389 B，`ddcc4edd…4c47` |
  | feed 文件 sha256 | `4f0d6f06aa92e67fcfe498533fd0117a805b54dfa7bc45846fdebcb7eee3fddf` |
  | 备份 | 同目录 `latest.json.bak`（原 0.1.51 feed，413 B） |
  | 回读 | 公网 GET 返回 0.1.52/53，与本地逐字段一致 |

- **回滚**：`node feed-publish.mjs rollback --server xxt@10.10.117.5 --root /www/wwwroot/xxt-desktop/desktop/xxt --rel ruyi/android/latest.json`（用 `.bak` 覆盖）。
- **遗留**：真机回归五项（红点、changelog、下载校验、自动更新、2 分钟节流）未做；后续发版确认存量老客户端已升级后，可改用 `--skip-apphub`。

## 责任人 / 更新时间

- 实施：AI；发布授权：用户。2026-11-16 立项，2026-11-19 收口。
