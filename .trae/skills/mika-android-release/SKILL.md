---
name: mika-android-release
description: 一键发布「如愿(Mika)」安卓包：从 build.gradle.kts 读版本 → gradlew 构建 → ossutil 上传 OSS → apphub 登记版本并回读校验。当用户说「发布新版本 / 发版 / 打包发布 Mika / 上传到 apphub / 让客户端能更新」时使用。
---

# 如愿(Mika) 安卓发布

把 Mika 安卓项目自动发布到 app-version-hub：构建、传 OSS、登记版本、回读校验，一条命令完成，无需手动上传。

## 前置条件（缺一不可，先自检）

1. **apphub 发布方账号**（`role=publisher`，本应用归属该账号名下）
   - 账号名建议 `mika-release`（一个智能体一个账号，最小权限，绝不用 admin）
   - 若尚未创建：见 [apphub-publish SKILL.md](../../../../../app-version-hub/skills/apphub-publish/SKILL.md) 的「先确认三件事」，需要跑 `seed:publisher` 或 `docker compose run --rm init` 创建 —— **这是生产数据变更，必须先单独和用户确认再执行**
2. **ossutil 已安装并配置好凭证**
   - 本机已装（`/Users/binny/.local/bin/ossutil`，v2.4.1）；没有 `~/.ossutilconfig` 时需先 `ossutil config` 填 AK/SK（RAM 子账号，最小权限，仅需 `chuangxin-prod` 桶的 `PutObject` 权限）
   - 或走 `ALICLOUD_ACCESS_KEY_ID` / `ALICLOUD_ACCESS_KEY_SECRET` 环境变量
3. **gradlew 可构建**（Mika 项目根有 `gradlew`，`app/build.gradle.kts` 是版本唯一事实源）

## 版本事实源

版本号**只改一处**：`app/build.gradle.kts` 顶部的

```kotlin
val ruyiVersionName = "0.1.48"
val ruyiVersionCode = 49
```

脚本从这两行 + `applicationId`（当前 `com.zl.ruiyiai.yuan`）自动读取，不手动传版本号，避免登记与 APK 不一致。

## 用法

在 Mika 项目根（`/Users/binny/workspace-sdd/mcp-demo/dsh/mika/Mika`）执行：

```bash
# 凭证用环境变量注入，不写进命令、不落仓库
export APPHUB_BASE_URL=http://127.0.0.1:3100        # 生产填 https://<PUBLIC_DOMAIN>/apphub
export APPHUB_USERNAME=mika-release
export APPHUB_PASSWORD='<该账号口令>'

SCRIPT=.trae/skills/mika-android-release/scripts/release.mjs

# 0) 先自检 apphub 连通 + 登录 + 权限
node "$APPHUB_CLI" doctor   # 或见 apphub-publish SKILL

# 1) 完整发布（构建 → 传 OSS → 登记 → 回读）
node "$SCRIPT" --changelog "本次更新说明"

# 2) 只看将做什么，不实际执行
node "$SCRIPT" --dry-run

# 3) 已有 APK，跳过构建
node "$SCRIPT" --skip-build

# 4) 发灰度渠道 / 先入库不对外
node "$SCRIPT" --channel beta --status draft
```

### 可选参数

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `--abi` | `universal` | 发布哪个架构包；可选 `arm64-v8a`（小包，仅 arm64 原生库） |
| `--changelog` | 空 | 给用户看的更新说明 |
| `--channel` | `official` | 渠道，灰度用 `beta` |
| `--status` | `published` | `published` 对外 / `draft` 只入库 |
| `--skip-build` | 关 | 跳过 gradle 构建，用已有 APK |
| `--url` | 无 | 指定下载直链并**跳过 OSS 上传**（本地联调、或包已托管在别处时用） |
| `--dry-run` | 关 | 只打印将做的事 |
| `--min-supported` | `0` | 低于该 versionCode 的客户端强制更新 |

### 环境变量一览

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `APPHUB_BASE_URL` | `http://127.0.0.1:3100` | apphub 服务地址 |
| `APPHUB_USERNAME` / `APPHUB_PASSWORD` | 无（必填） | 发布方账号口令 |
| `APPHUB_CLI` | 已知仓库路径 | apphub-publish.mjs 位置 |
| `OSS_BUCKET` | `chuangxin-prod` | OSS 桶 |
| `OSS_REGION` | `cn-hangzhou` | 地域 |
| `OSS_ENDPOINT` | `https://oss-cn-hangzhou.aliyuncs.com` | 上传 endpoint |
| `OSS_PREFIX` | `ai/mika/android` | 对象键前缀 |
| `OSS_PUBLIC_BASE` | `https://oss-chuangxin.thchengtay.com` | 下载域名（登记进 apphub） |
| `OSSUTIL` | `ossutil` | ossutil 可执行文件 |
| `APP_NAME` | `如愿` | 应用显示名 |
| `MIKA_ROOT` | 当前目录 | 项目根 |

## 直链与对象键约定

- 上传对象键：`ai/mika/android/mika-<versionName>-<abi>.apk`（ASCII 安全，不含中文）
- 登记进 apphub 的下载直链：`https://oss-chuangxin.thchengtay.com/ai/mika/android/mika-<versionName>-<abi>.apk`
- 说明：app-version-hub **不托管 APK 二进制**，只登记「元数据 + 直链」；上传这步由 ossutil 完成

## 故障排查

| 现象 | 原因 / 处理 |
| --- | --- |
| `缺少 APPHUB_USERNAME / APPHUB_PASSWORD` | 未注入环境变量，或用了 admin 账号。用 `mika-release` 的 publisher 凭证 |
| ossutil `region must be set` / `Invalid signing region` | `OSS_REGION` 与 `OSS_ENDPOINT` 要和桶地域一致（cn-hangzhou） |
| ossutil 认证失败 | 未配 `~/.ossutilconfig`，先 `ossutil config` 填 AK/SK |
| apphub `登录失败 HTTP 401` | 口令错或 token 过期（24h），重跑即重新登录 |
| apphub `403 FORBIDDEN` | 该 appKey 属于另一个账号。确认应由哪个 publisher 账号发 |
| apphub `409 CONFLICT`（版本冲突） | 该 versionCode 已存在（上次其实成功了）→ 直接回读确认；要重发先升 `ruyiVersionCode` |
| apphub `404 应用不存在` | 先跑 `app:ensure`（脚本已自动包含这步） |
| 找不到产物 APK | 构建失败或 `--abi` 选错；检查 `app/build/outputs/apk/release/` |
| gradle 报 `What went wrong: 25.0.4` | 默认 JAVA_HOME 是 JDK 25，Gradle 8.11.1 不认。构建前指定 `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home` |

## 硬规则

- 凭证只走环境变量，不写进命令、不落仓库、不进日志
- 不用 admin 账号做机器发布
- 已发布的 `versionCode` 不回退、不重用；回退旧版＝登记新版本，或把版本 `releaseStatus` 改 `withdrawn`
- 终止操作只有两种：归档应用 / 置 `withdrawn`，**不要删库**
- 创建 `mika-release` 账号是生产数据变更，必须先单独确认再执行

## 参考

- 发布 CLI 与认证：`app-version-hub/docs/agent-publishing.md`、`app-version-hub/skills/apphub-publish/SKILL.md`
- OSS 上传：`~/.workbuddy/skills/alicloud-storage-oss-ossutil/SKILL.md`
