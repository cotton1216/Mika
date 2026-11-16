# T01 · Mika 安卓一键发布技能（mika-android-release）

| 字段 | 内容 |
| --- | --- |
| 任务ID | T01 |
| 目标 | 让 Mika 安卓打磨后一键发布到 app-version-hub，无需手动上传 |
| 状态 | doing |
| 责任人 | 待确认（用户） |
| 更新时间 | 2026-11-15 |

## 目标与验收标准

- 目标：新增一个本地技能 `mika-android-release`，一条命令完成「构建 APK → 上传 OSS → apphub 登记 → 回读校验」。
- 验收标准：
  1. `release.mjs --dry-run` 能正确读取 build.gradle.kts 的 appKey/versionName/versionCode，并打印完整动作。
  2. 实际执行时，产物 APK 上传到 `oss://chuangxin-prod/ai/mika/android/`，登记进 apphub 后 `GET /api/v1/update/check` 回读 `hasUpdate=true` 且 versionCode 正确。
  3. 凭证全程走环境变量，不落仓库。

## 范围

### 本期做

- 新增编排脚本 `.trae/skills/mika-android-release/scripts/release.mjs`（gradlew → ossutil → apphub-publish）。
- 新增技能说明 `.trae/skills/mika-android-release/SKILL.md`（前置条件 / 用法 / 故障排查）。

### 本期不做

- 不实现「长期 API Key / 免登录无人值守发布」（apphub 当前无该能力）。
- 不实现多应用 / 多平台泛化发布（本技能只针对 Mika 安卓 official 渠道）。
- 不在 app-version-hub 侧新增「托管 APK 二进制」能力（刻意产品边界，保持只登记元数据 + 直链）。
- 不新建 `mika-release` 账号（生产数据变更，见「阻塞/依赖」）。

## 方案要点

- 复用两个既有工具，本技能只做编排：
  - `ossutil 2.x` 上传 APK 到 `chuangxin-prod`（cn-hangzhou）。
  - `app-version-hub/scripts/apphub-publish.mjs` 负责登录、`app:ensure`、`publish`（自动 sha256/size、回读校验）。
- 版本事实源 = `app/build.gradle.kts` 顶部的 `ruyiVersionName` / `ruyiVersionCode` / `applicationId`（当前 `com.zl.ruyiai.yuan`）。
- OSS 对象键 `ai/mika/android/mika-<version>-<abi>.apk`（ASCII 安全），下载直链 `https://oss-chuangxin.thchengtay.com/<key>`。
- apphub 登记：`--code` 直接传 build.gradle 的 versionCode（不依赖服务端自增），`--channel official`，`--status published`。
- 凭证：apphub 走 `APPHUB_USERNAME`/`APPHUB_PASSWORD` 环境变量；OSS 走 ossutil 自身 `~/.ossutilconfig` 或 `ALICLOUD_*` 环境变量。

## 关键决策与取舍

1. **只发 universal 全架构包为默认**（`--abi universal`），保证所有设备可更新；`arm64-v8a` 小包作为可选项。理由：apphub 每个 (platform,channel,versionCode) 只登记一个主 URL，全架构包是安全默认。
2. **对象键去中文化**：APK 文件名 `如愿-<ver>-<abi>-release.apk` 含中文，登记成 `mika-<ver>-<abi>.apk`，避免 URL 编码问题。
3. **appKey 从 build.gradle 读**，不硬编码：实际是 `com.zl.ruyiai.yuan`（非 `com.zl.ruiyi.yuan`），避免登记错应用。

## 已否决方案

- 「app-version-hub 直接托管 APK 二进制」：否决，hub 刻意只登记元数据 + 直链，托管二进制超出其产品边界。
- 「用 admin 账号发布」：否决，违反最小权限，publisher 账号才能按 appKey 隔离归属。

## 阻塞 / 依赖

1. **`mika-release` publisher 账号**：本地库已创建（写入本地 `.env` 的 `PUBLISHER_ACCOUNTS` 并跑 `seed:publisher`）。**生产库仍未创建**，属生产数据变更，执行前须单独取得用户确认。
2. **OSS 凭证未配置（首真发版阻塞）**：本机无 `~/.ossutilconfig`、无 `ALICLOUD_ACCESS_KEY_*`。用户决定「暂不验证，发版时再配」，故 `ossutil cp` 这一步尚未经真实验证。
3. **apphub 服务地址**：本地默认 `http://127.0.0.1:3100`；生产需注入 `APPHUB_BASE_URL=https://<PUBLIC_DOMAIN>/apphub`。
4. **构建需 JDK 17**：默认 JAVA_HOME 为 JDK 25 时 Gradle 8.11.1 直接失败，需 `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`。

## 影响评估

1、<b>新建 mika-release 账号</b>：生产库新增一条 publisher 用户记录，归属隔离，不影响现有数据。
2、<b>OSS 对象新增</b>：仅向 `chuangxin-prod` 桶 `ai/mika/android/` 前缀写入新对象，不覆盖不删除现有对象。
3、<b>apphub 登记新版本</b>：`POST /api/apps/{key}/versions` 非幂等，重复提交 409；`versionCode` 只增不减，需确保 build.gradle 每次发版递增。
4、<b>无回退风险</b>：本技能为新增只读编排脚本，不改动 app-version-hub 与 Mika 现有代码逻辑。

## 回滚方案

- 触发条件：发布后客户端检查更新异常 / 直链不可达。
- 执行动作：将已登记版本 `releaseStatus` 改为 `withdrawn`（下架），或登记一个回退版本指向旧直链；不删库、不回退 versionCode。
- 代码回滚：删除 `.trae/skills/mika-android-release/` 目录即可，不影响既有构建与发布流程。

## 进度快照

- 目标与验收：一条命令完成「构建 → OSS → 登记 → 回读」；本地链路已于 2026-11-15 验证通过（OSS 上传步骤除外）。
- 已完成：
  - `release.mjs`（含 `--skip-build` / `--dry-run` / `--abi` / `--url`）、`SKILL.md`、本任务文档、`TASKS.md`。
  - 本地 hub 起服务（`127.0.0.1:3100`，`/healthz` 正常）；本地库创建 `mika-release` publisher 账号并可登录（doctor 通过）。
  - 真机构建成功：`app/build/outputs/apk/release/如愿-0.1.48-universal-release.apk`（132,484,705 字节）。
  - 本地登记成功：应用 `com.zl.ruyiai.yuan` id=3，版本 v0.1.48 code=49 id=5，sha256 已写入，回读 `hasUpdate=true / policy=optional`。
- 进行中 / 卡点：无进行中动作；真实 `ossutil cp` 未验证（缺 AK/SK）。
- 已否决方案：hub 托管二进制；admin 账号发布；用生产桶做本地测试（用户选「暂不验证」）。
- 未验证假设：`ossutil cp --region cn-hangzhou -e https://oss-cn-hangzhou.aliyuncs.com` 参数组合与公网下载域名 `oss-chuangxin.thchengtay.com` 可达性，均需首次真发版验证。
- 下一步：① 生产库创建 `mika-release`（需用户确认）；② 配 OSS AK/SK 并先用 `ai/mika/android/_local-test/` 传一个小文件验证；③ 正式发布 v0.1.48。

## 相关代码与文档（绝对路径）

- 技能脚本：`/Users/binny/workspace-sdd/mcp-demo/dsh/mika/Mika/.trae/skills/mika-android-release/scripts/release.mjs`
- 技能说明：`/Users/binny/workspace-sdd/mcp-demo/dsh/mika/Mika/.trae/skills/mika-android-release/SKILL.md`
- 发布 CLI：`/Users/binny/workspace-sdd/mcp-demo/dsh/app-version-hub/scripts/apphub-publish.mjs`
- 接入指南：`/Users/binny/workspace-sdd/mcp-demo/dsh/app-version-hub/docs/agent-publishing.md`
- 版本事实源：`/Users/binny/workspace-sdd/mcp-demo/dsh/mika/Mika/app/build.gradle.kts`
- OSS 上传技能：`/Users/binny/skills/alicloud-storage-oss-ossutil/SKILL.md`
