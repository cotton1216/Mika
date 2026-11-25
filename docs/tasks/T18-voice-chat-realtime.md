# T18 — 豆包实时语音对话接入与模块重构规范化（升版 0.1.54/55 双通道发布）

## 变更类型与基本信息
- **类型**：新增功能 / 纯优化 / 生产发布
- **操作人**：AI（Trae）
- **原代码负责人**：Mika 移动端团队既有作者
- **提交日期**：2026-11-24
- **Commit ID**：`afe3da1a`、`6b05341b`、`779ae3fc`

## 后续版本 0.1.55 / code56（2026-11-24）
- **Commit**：`779ae3fc`
- **内容**：全屏数字人态（多彩呼吸灯占位形象、声波纹、字幕与通话控件）；浮窗与小胶囊改用同款呼吸灯；修复同一句话重复显示（completed 后残余 delta 不再拼进已清空累积）。
- **验证**：`:app-main:compileReleaseKotlin` 通过；生产签名 CN=Yuan AI 校验通过。
- **双通道发布**：APK `https://oss-chuangxin.thchengtay.com/ai/ruyi/android/ruyi-0.1.55-universal.apk`（133,219,241 B，sha256 `6d32eedfe423…bd2`）；静态 feed 与 Apphub 均回读 0.1.55/56。

## 后续版本 0.1.56 / code57（2026-11-25）
- **Commit**：`18f33cff`、`9f16f95c`（含发版脚本修复）
- **发版脚本修复**：`--yes` 被下一个参数吞掉导致确认闸门静默不过；dry-run 的 `process.exit(0)` 缩进错误使非 dry-run 也在打印后直接退出（曾导致把旧 APK 当成新版本发布）。
- **渠道分工落地**：`--env beta` 新增，把调试包（`com.zl.ruyiai.yuan.dev`，与正式版并排）登记到 as 的 beta 渠道；`--env prod` 追加把同一正式 APK 登记到 as 镜像台账 official 渠道，缺凭证直接失败不静默跳过。
- **三处发布**：APK `https://oss-chuangxin.thchengtay.com/ai/ruyi/android/ruyi-0.1.56-universal.apk`（sha256 `3e146901d3fa…8e821e90`）；`apps.kfxh.work` official、`ai.thchengtay.com/apphub` official、静态 feed 均回读 0.1.56/57。
- **历史补齐**：as 的 official 渠道由 0.1.51 补齐到 0.1.55 后随本版升至 0.1.56。

## 后续版本 0.1.57 / code58（2026-11-25）
- **Commit**：`7f1ec4e9`（绿色呼吸灯改动）+ 本版升版提交
- **内容**：浮窗、全屏数字人与声波条去掉彩虹（`sweepGradient` 色相循环与紫/粉/青轮换），统一为品牌绿 `#22C55E` + 深绿 `#10B981` 双圈，靠明暗叠层次；删除不再使用的 `hueCycle()`。
- **三渠道 release 发布**：APK `https://oss-chuangxin.thchengtay.com/ai/ruyi/android/ruyi-0.1.57-universal.apk`（133,218,649 B，sha256 `e5262d88daac…ef9d`，签名 CN=Yuan AI）；静态 feed、`ai.thchengtay.com/apphub` official、`apps.kfxh.work` official 回读均 0.1.57/58。
- **Dev 仅推 as**：调试包 `app-main-debug.apk`（149,584,211 B，sha256 `3c6c7e28d345…04e4`）登记到 `apps.kfxh.work` beta 渠道 id=11，未改动静态 feed 与主 apphub。
- **过程问题**：手工 `assembleDebug` 漏 `-PdevInstall`，产出的调试签名包覆盖了手机上的正式版（`com.zl.ruyiai.yuan` 被卸载）；已重打带 `.dev` 后缀的包恢复并存。此后手工装 Dev 必须带该参数。

## 后续版本 0.1.58 / code59（2026-11-25）
- **Commit**：`8da5f4b4`（Dev 改读 as beta + AppHub 双格式解析）+ 本版升版提交
- **修复**：「Dev 上版本更新检测失败」。根因两层：① Dev 包内置的是生产静态 feed，而 Dev 只发布在 as beta，两者不同源，永远查不到自己；② 之前的 `updateFeedUrl` 显式注入被 Gradle 缓存到了后续 debug 构建。
- **改动**：
  - `app-main/build.gradle.kts`：`-PdevInstall` 的 debug 变体默认注入 `https://apps.kfxh.work/api/v1/update/check?appKey=com.zl.ruyiai.yuan&platform=android&channel=beta`；显式 `-PupdateFeedUrl` 仍优先；正式包仍走静态 feed。
  - `AppHub.parse`：先判空对象；响应含 `latest` 时按 app-version-hub 结构解析（`versionName`/`packageSize`/`sha256`/`forceUpdate`/`download.url`），否则按扁平 feed。
  - 各语言补齐 `voice_live_title`/`voice_live_line`/`voice_call_open` 8 种语言译文；模块重命名收尾（测试里的 `core/src`、`app/src` → `lib-core/src`、`app-main/src`）。
- **Dev 发布（仅 as beta）**：`app-main-debug.apk` 149,587,015 B，sha256 `997470cf8ace…d966`，登记 `apps.kfxh.work` beta 渠道 id=12，0.1.58/59；静态 feed 与主 apphub 未改动。
- **真机验证（Xiaomi 24122RKC7C）**：
  1. 装 0.1.57/58 的 Dev 包后点「检查更新」→ 缓存 `update-v2.json` 的 `apkUrl` 指向 `/beta/ruyi-0.1.57-universal.apk`、`notes` 为 beta 的 changelog，证明请求已到 as beta 且 hub 结构解析正确；结果「已是最新」。
  2. beta 升到 0.1.58/59 后同一台机器再点「检查更新」→ 顶部提示「发现新版本 0.1.58」，弹出更新说明，红点与右侧「0.1.58 可更新」同时出现。
  3. 在弹窗内点「下载更新」→ 150 MB 下载完成 → 系统安装页「版本：0.1.57 -> 0.1.58」→ 安装后 `dumpsys` 显示 `versionName=0.1.58 versionCode=59`。
- **未纳入本次**：`./gradlew :lib-core:test` 存在 27 处既有编译错误（`AgentLoopTest` 的 `completeStreaming` 覆盖签名、`AnthropicModelTest` 缺 `onText`），已用 `git stash` 验证为模块重命名前既有问题，本次未改动。

## 目标与交付范围
1. **模块结构规范重命名**：`app/` → `app-main/`，`core/` → `lib-core/`，`ruiyi-remote/` → `app-ruyi-remote/`，`ruiyi-remote-core/` → `lib-ruyi-remote-core/`，`ruiyi-remote-ui/` → `lib-ruyi-remote-ui/`。
2. **实时语音对话接入**：接入豆包全双工实时语音会话，支持模型统一配置、连接测试、麦克风权限守卫与动态界面。
3. **发版流水线修复与升级**：
   - 修复 `release.mjs`、`.gitattributes`、`release-assets.sh` 中旧模块路径引用。
   - 生产静态 feed 部署主机适配 `~/.ssh/config` 别名 `xxtva`（根路径 `/opt/desktop-deploy/www/desktop/xxt`）。
   - 版本号递增至 `0.1.54` / `versionCode 55`。
4. **双通道生产发布**：
   - Apphub（official/published）：已登记并回读通过。
   - 静态 feed（`latest.json`）：已完成阶段上传、sha256 校验、原子切换与公网 HTTP 回读。

## 生产验证证据
- **APK 直链**：`https://oss-chuangxin.thchengtay.com/ai/ruyi/android/ruyi-0.1.54-universal.apk`
- **静态 feed 线上地址**：`https://ai.thchengtay.com/desktop/xxt/ruyi/android/latest.json`
- **Apphub 检查更新地址**：`https://ai.thchengtay.com/apphub/api/v1/update/check?appKey=com.zl.ruyiai.yuan&platform=android&channel=official`
- **产物哈希**：`6fa20fe38422f40907a14c0c11641d0861a4c3efbc1c396c135bf3f1e7973db8`，体积 133,101,921 字节。
