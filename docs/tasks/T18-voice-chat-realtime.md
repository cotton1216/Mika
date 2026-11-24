# T18 — 豆包实时语音对话接入与模块重构规范化（升版 0.1.54/55 双通道发布）

## 变更类型与基本信息
- **类型**：新增功能 / 纯优化 / 生产发布
- **操作人**：AI（Trae）
- **原代码负责人**：Mika 移动端团队既有作者
- **提交日期**：2026-11-24
- **Commit ID**：`afe3da1a`、`6b05341b`

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
