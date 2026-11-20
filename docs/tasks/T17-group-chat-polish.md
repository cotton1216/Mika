# T17 — 群聊体验补齐（下拉选人 / 全体广播 / 全会话页分组 / 富文本补齐）

## 目标
承接 T16 真群聊合并视图，按真机反馈与用户追问补齐一组体验缺口：
1. 「发给谁」从横向成员芯片改为**下拉选择**；
2. 支持**不指定成员**——默认**广播给全体成员**（底层逐成员 `session/prompt`）；
3. 「全部会话」工作区页也按与抽屉**同一维度**分组（抽屉同款全量）；
4. 诊断「只有一个群、没有专家」的数据源限制（不改代码）；
5. 修复群聊合并页**看不到图片 / SVG / Markdown**——投影层丢了协议图片块与 `tool-result` 嵌套内容。

## 已完成项与方案

### 1. 发给谁 → 下拉选择（dev 包已装机验证）
- `GroupChatScreen.kt` 的 `TargetPicker` 由横向 chips 改为 `DropdownMenu`：
  - 顶部「发给谁」标签 + 当前目标按钮 + 旋转 90° 的 chevron；
  - 下拉第一项「全体成员」（`onSelect(null)`），其下列出全部成员；
  - 当前项用 `p.accent` 高亮；按钮文案与底部输入框 placeholder 同步。
- 移除 `horizontalScroll` 依赖，新增 `DropdownMenu/DropdownMenuItem/GlyphChevron/rotate` 导入。

### 2. 不指定成员 → 广播全体（dev 包已装机验证）
- `GroupChatState.targetSessionId: String?`，`null` = 广播全体（默认），`open()` 重置为 null。
- `setTarget(sessionId?)`：切回全体传 null。
- `send(text)`：`targetSessionId != null` 只发一个；否则对 `members.map{ sessionId }` 逐个 `api.prompt`；
  单个失败不影响其余，至少一个成功即 `sentMarker++` 并 `refresh()`；全失败才显示错误并保留草稿。
- `GroupTimelineControllerTest` 覆盖：默认广播全部、指定单个、`setTarget(null)` 切回、空白忽略、无成员忽略。

### 3. 全部会话页也分组（主体完成，真机复验待设备恢复）
- 新增 `GroupedSessions.kt`：把分组渲染从 `SessionDrawer` 抽成共享 `LazyListScope.groupedSessionItems(...)`，
  并把 `SessionRow / FolderHeaderRow / DayHeaderRow / GroupHeaderRow / DimensionSwitcher / toggleKey / dayLabel / statusLabel` 提升为 internal。
- `SessionDrawer.kt`：删除重复渲染代码，LazyColumn 直接调 `groupedSessionItems`。
- `HostWorkspaceScreen.kt`：签名新增 `folderView / dimension / onToggleFolder / onSelectDimension / onOpenGroup`；
  `ConnectedBody` 加本地 `collapsedKeys`，加 `DimensionSwitcher`，LazyColumn 用 `groupSessions(...)` + `groupedSessionItems`。
- `AppNav.kt`：`Screen.Workspace` 分支透传 folderView/dimension/切换回调，`onOpenGroup` 压入 `Screen.GroupChat`。
- 效果：抽屉与工作区页共享同一维度状态，文件夹常驻 + 天/状态/目录/群四维 + 折叠 + 群聊入口。

### 4. 多群 / 专家诊断（只诊断，不改代码）
- 手机端数据源只有 `session/list`，只能靠标题 `成员名 · 群名` 聚合出当前可见群，拿不到专家。
- PC 的「多群 + 专家」来自 DS Bot 插件 API（`dsBot/call`、`dsBot/feed`）；relay 白名单 `HARNESS_REMOTE_ALLOWLIST` 未放行这两个端点。
- PC 同款完整 Bot 模式需跨仓改造（xiaotong-bot Remote 适配 + relay 白名单 + host 插件部署），本次不授权、不动。

### 5. 群聊合并页富文本补齐（本轮代码完成）
根因：T16 `projectTimeline` 只用 `textOf()` 提纯文本，未复用 `messageImages()`，也未展开 `tool-result` 嵌套 content，
导致协议图片块与嵌套在工具结果里的 Markdown / SVG 正文全部丢失。修复：
- `GroupTimeline.kt`：
  - `TimelineEntry` 加 `images: List<MessageImage>` 与 `imageSourceSessionId: String?`；
  - `textOf()` 展开 `tool-result.content` 后再取 text part；
  - `projectTimeline()`：user/assistant 分支都调 `messageImages()`；`text 与 images 都空`才跳过；
    广播去重键从 `(text,time)` 升为 `(text,time,图片数)`，避免两条纯图广播互吞；
    两类条目都写入 `imageSourceSessionId`（广播也记首个成员会话，供 UI 读图）。
- `GroupTimelineController.kt`：加 `loadSessionImage(sessionId, image)` 走 `api.loadImage`。
- `GroupChatScreen.kt` 的 `TimelineBubble`：气泡内容改为 Column——
  - 先渲染 `entry.images`（`AttachmentImagePreview`，loader 用 `imageSourceSessionId`）；
  - 再渲染 `MarkdownContent(entry.text, onLoadImage = { controller.loadWorkspaceImage(source, it) })`；
  - SVG / 代码块 / 表格由 MarkdownContent 既有能力处理（`libs.androidsvg` 依赖未动）。

## 验收
- 单测：`:ruiyi-remote-ui:testDebugUnitTest` **43/43 通过**（GroupTimelineTest 由 7 增至 11 例，新增 4 例富文本投影测试；
  GroupTimelineControllerTest 6 例；SessionGroupsTest 15 / MarkdownInlineTest 6 / FolderStoreTest 5）。
- 编译：`:app:assembleDebug -PdevInstall` **BUILD SUCCESSFUL**，产物 `app/build/outputs/apk/debug/app-debug.apk`（160M，包名 `com.zl.ruyiai.yuan.dev`）。
- 真机（此前）：下拉选人 / 全体广播 dev 包已装机验证（下拉展开、成员列表、当前项高亮、目标切换、placeholder 同步）。
- **模拟器验证（2026-11-19 19:00，emulator-5554 / sdk_gphone64_arm64 / AVD dshgo_android）**：
  - 前提：真机 USB 断开、三端口无设备，用户明确要求改用模拟器并同意装机（报序列号+型号后确认）。
  - dev APK 安装成功、App 正常启动到首页、无应用崩溃（日志中异常均为系统服务/网络探测，与 App 无关）。
  - 模拟器有账号登录态与主机列表（「大苹果」「Ai-Host」在线），成功连上「大苹果」进入远程会话页。
  - 全会话页分组**确认可用**：「这台电脑上的会话 53→54 条」（实时刷新）、四维切换「按天/按状态/按目录/按群」高亮正常；切到「按群」维度时工作区页会话行 title 不带 `· 群名`、未聚成群块（与抽屉页表现不同，见下）。
  - 会话抽屉里「按群」维度**确认可用**：出现群块「科学发展史五学科讨论 · 6 人」+ 6 成员会话 + 「进群聊」按钮（content-desc「打开…的合并群聊」）。
  - 群聊合并页**确认可用**：标题「6 位成员 · 本地合并」、边界说明、「发给谁」下拉（默认「全体成员」）、6 成员发言按时间合并成单一时间线、发言人正确、含 @提及、「我」的广播右对齐蓝气泡、底部「发给 全体成员...」输入框。
  - **富文本图片端到端验证通过（19:35）**：初始整条时间线（16:51–18:35）全是纯文字、无图可渲染。用户同意「发一条测试消息、不打扰」后，在「数学代表」成员会话用「添加图片」发了一张测试图（`test_handwriting.png`，530KB<2MiB）+ 文字 `IMG_RENDER_TEST_1119`（会话页 `ChatScreen` 确认图片作为协议图片块正常上传/显示/Agent 已读图回复）。回到群聊合并页刷新并滚到底：**出现一条右对齐标「我」、19:29 的条目，内嵌完整渲染的图片**（UI dump 出现 `content-desc="test_handwriting.png"` 的 ImageView，非空白/非占位），下方「数学代表 19:29」回复正常。证明 `messageImages` 提取 → `TimelineEntry.images` + `imageSourceSessionId` → `TimelineBubble` 的 `AttachmentImagePreview` + loader 全链路可用。**SVG/Markdown 正文渲染沿用 `MarkdownContent`（T15 已单独验证），本次未单独造 SVG 数据。**
  - 造数据的副作用：往「大苹果」的「数学代表」会话发了一条带图测试消息（用户已同意「发吧」）。
  - 期间模拟器两次遇到中继 `TRANSPORT_CLOSED` 掉线并自动重连（软渲染 + 弱网），重连后功能正常，非 App 缺陷。

## 待办 / 阻塞
- **真机仍离线**：5037/5038/5039 三个 adb server 均无设备（USB 断开），重启 5038 仍空。真机复验需设备恢复后按约定报序列号+型号单独确认（模拟器已覆盖全部功能验证）。
- SVG 端到端未单独造数据（渲染能力在 `MarkdownContent`，T15 已验证图片路径；SVG 代码路径未动）。
- 多群 / 专家完整方案未授权、未动（见诊断项 4）。

## 回滚
- 本轮增量均可独立回退：`GroupedSessions.kt` / `GroupChatScreen.kt` / `GroupTimeline*.kt` / 相关测试为 T16 之后的追加改动。
- 富文本补齐回退点：去掉 `TimelineEntry.images/imageSourceSessionId`、`textOf` 的 tool-result 展开、`TimelineBubble` 的图片渲染块，即回到 T16 纯文本时间线。
- 未提交、未推送；无 commit/push 授权。

状态：代码完成 + 单测 43/43 + 编译通过；模拟器（emulator-5554）验证全部通过——分组/下拉/广播/合并时间线/富文本图片端到端（造一条带图群消息，群聊页正确渲染）。未提交、未推送。更新时间 2026-11-19。
