# T16 — 群聊合并时间线 MVP（手机端本地合并视图）

## 目标
把 PC「小通 Bot 模式」里一个群的多个成员会话，在手机端合并成**一个按时间排序的聊天窗**，每条消息标注发言人，并支持「发给谁」选择目标成员。解决 T14「按群」只是列表聚合、点进去仍是各自独立对话框、不像真群聊的问题。

## 路线边界（用户确认：合并时间线 MVP）
- **A 路线**：纯手机端改造，零协议改动。不接 DS Bot、不改 relay 白名单、不改 host 插件。
- **非实时群房间**：实现为「打开群聊页时刷新」的本地合并视图，不做 follow 常驻实时推送。页面顶部明确标注「本地合并视图 · {主机} 上 N 位成员的会话按时间拼成一条讨论，非实时群房间」。
- **数据源**：同一群的多个 xiaotong-bot 成员会话，`session/list` 标题为 `${成员名} · ${群名}`（T14 真机已验证格式）。
- **装机验证**：沿用约定——报序列号+型号单独确认后才安装。

## 方案
- `session/GroupTimeline.kt`（新增，纯数据层）：
  - `parseGroupMember(sessionId, title)` 用 `indexOf(" · ")` 取**首次**出现切分（与 T14 `parseGroupTitle` 严格一致，避免群名自带 ` · ` 时分群不一致），得 `GroupMemberRef(sessionId, name, group)`。
  - `eventsFromFrame(frame)`：`snapshot` → `eventsFromSnapshot`；`event` → 取 `frame.event`；其余空。复用同包 `Json.kt` 的 `str/obj/long` 扩展（删掉本文件私有同名扩展，消除 overload ambiguity）。
  - `projectTimeline(members)`：合并多个成员消息为 `List<TimelineEntry>`——
    - `user/message` 群广播按 `(text, time)` 去重，`speaker = null`（UI 标「我」）；
    - `assistant/message` 为成员发言，`speaker = 成员名`，保留 `sessionId`；
    - 跳过系统合成消息/空白/控制事件；content array 拼接为文本；
    - 按 `time → kind → id` 稳定排序。
- `session/GroupTimelineController.kt`（新增）：
  - `GroupChatState(group, members, entries, loading, error, targetSessionId, sending, sendError, sentMarker)`。
  - `open(group, memberSessions)`：解析成员 → `loadMembers` 对每个成员 `api.follow(id).first()` 取首帧 snapshot，`eventsFromFrame` 提事件，`projectTimeline` 合并。
  - `setTarget(sessionId)`：切换发送目标成员。
  - `send(text)`：`api.prompt(target, text)` → `sending=false` + `sentMarker` → `refresh()`；空白文本或无目标忽略。
  - `loadWorkspaceImage(sessionId, path)`：复用 T15 `SessionApi.loadWorkspaceImage` 渲染成员发言里的 markdown 图片。
- `ui/Navigation.kt`：`Screen.GroupChat(hostId, group, members)`。
- `ui/screen/GroupChatScreen.kt`（新增）：页头（群名/成员数/「本地合并」）、成员芯片「发给谁」、`LazyColumn` 时间线（广播右对齐标「我」、成员发言左对齐标发言人 + markdown 图片）、底部 composer。
- `ui/screen/SessionDrawer.kt`：`GroupBlock` 组头加「进群聊」按钮 → `onOpenGroup(group, items)`；群成员行仍点进原独立会话（不误伤私聊）。
- `AppNav.kt`：`rememberGroupTimelineController(connection)` 注入；`openGroup` 压栈 `Screen.GroupChat`；掉线同时弹掉 Chat/GroupChat。

## 验收
- 单测：新增 `GroupTimelineTest` 7 例（标题按首次分隔符切分 / 成员发言标注 / 广播去重 / 时间排序 / 过滤合成空白控制事件 / content array 拼接 / snapshot-event frame 提取）+ `GroupTimelineControllerTest` 3 例（open 合并去重 / send 定向并刷新 / 空白或无目标忽略）。全量 **36/36** 通过（FolderStore 5 + MarkdownInline 6 + SessionGroups 15 + GroupTimeline 7 + GroupTimelineController 3）。
- 编译：`:app:assembleDebug -PdevInstall` BUILD SUCCESSFUL（4s）。APK：`com.zl.ruyiai.yuan.dev` versionCode 53 / versionName 0.1.52，144M。
- 真机（dev 包，装于 `33325df` / Redmi `24122RKC7C` / REDMI K80 Pro / Android 17 / USB 5038；报序列号+型号经用户确认后覆盖 dev 包、正式版不动）：
  - 宿主首页 → 侧边栏「远程控制」→ 连「大苹果」→ 会话抽屉「按群」维度；
  - 群头「进群聊」进入合并视图，标题「科学发展史五学科讨论 · 6 位成员 · 本地合并」；
  - **6 位成员发言合并成单一时间线**：小通（发起讨论）、数学/物理/化学/生物/天文学代表依次发言，发言人标签正确、@提及保留、按时间排序（16:51–16:52）；
  - 「发给谁」芯片切换：点「化学代表」→ 高亮切换 + 底部提示同步为「发给 化学代表...」；
  - 未做真实发送（避免打扰群里真实成员），发送逻辑由 `GroupTimelineControllerTest` 覆盖。
- 排错过程（均已修）：① `eventsFromFrame` 单表达式 `let` 无法推断类型 → 改显式代码块；② 本文件私有 `str/obj/long` 与 `Json.kt` 扩展 overload ambiguity → 删私有扩展；③ 标题解析初版用 `lastIndexOf` 与 T14 不一致 → 改 `indexOf`；④ `HorizontalScrollScope` 非合法扩展目标 → 改普通 `TargetPicker` + `horizontalScroll`；⑤ Compose padding `horizontal=` 与 `top/bottom=` 混用 → 四边分别命名；⑥ 控制器测试用 `scope.launch` 未推进协程 → 加 `advanceUntilIdle()`；⑦ nested `FakeTransport` 不能调外层实例方法 → 内联空快照；⑧ 真机 tap 用截图缩放坐标无效 → 用物理像素（1440×3200，约 3×）。

## 影响与回滚
- 仅改 ruyuan 工作树 `wt-folder-model`（分支 `folder-model`），零协议改动，不碰 relay / host / 正式版。
- 不引入实时推送、不改数据持久化；关闭群聊页即丢弃合并结果，下次进入重新拉取首帧。
- 回滚：移除 `GroupTimeline*` / `GroupChatScreen` / `Screen.GroupChat` / 群头入口即可；T14「按群」维度独立保留。

## 状态
done（代码 + 36/36 单测 + 编译 + 真机装机验证均通过；**未提交、未推送**，等用户最终验收后单独授权提交）。真群聊完整方案（xiaotong-bot Remote 适配 / relay 白名单 / host 插件部署 / DS Bot 四层依赖）仍未授权、未动。
