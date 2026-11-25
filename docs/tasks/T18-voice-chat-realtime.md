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
