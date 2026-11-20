# T14 — Remote 第四分组维度「按群」

## 目标
承接 T13，给 Remote 会话抽屉的维度切换器加第四档「按群」，把小通 Bot 群成员会话从平铺状态聚合成一组，解决用户反馈的「群组在手机端被展开」。

## 背景与依据
- 真机只读抓取 `session/list` 证实：小通 Bot 群成员会话的 `title` 被钉成 `成员名 · 群名`（实证：`天文学代表 · 科学发展史五学科讨论` 等 6 条）。依据是 `xiaotong-bot/team.ts` 的 `bindMemberConversation(..., "${bot.name} · ${group.name}", ...)`。
- `SessionSummary.displayTitle` 在未空白时直接返回 `title`，无二次加工，所以 UI 文本即 `session/list` 原文。
- 上一轮已评估：PC 同款 DS Bot 模式需跨 3 仓（relay 双侧白名单 + host 插件 + ruyuan UI），属生产链路变更、需单独授权；本任务走零协议改动的轻量聚合路线。

## 方案
- `SessionGroups.kt`：新增 `GroupDimension.GROUP`、`GroupBlock(group, members, items)`、`parseGroupTitle`（只认空格包裹的 ` · `，成员名/群名非空，取首次出现切分）。
- 聚合口径：`session/list` 里 title 符合格式的会话按群名归堆；**≥2 个不同成员名**才成 `GroupBlock`；单个成员（普通会话标题里偶然的 `A · B`）与无分隔符的普通会话一律退回按天块，防误伤。
- 每条会话恰好出现一次；文件夹块在所有维度下常驻顶层（继承 T13 契约）；同一成员多条会话在 `members` 里去重。
- UI：维度切换器加「按群」档；`LazyColumn` 分发加 `GroupBlock` 分支，组头显示「群名 · N 人 / M 条会话」，成员缩进一档；折叠态复用 T13 的 `collapsedKeys`（key = `group-<群名>`）。
- 未改 SharedPreferences 结构：`GroupDimensionStore` 已按枚举 name 落盘，新增枚举值自动兼容，旧数据无 `GROUP` 时仍默认 DAY。

## 验收
- 单测：`SessionGroupsTest` 新增 4 例（真机格式聚合 / 单成员不成群回退按天 / 普通会话不误伤且唯一 / 文件夹常驻+成员去重）；全量 20/20 通过（FolderStore 5 + SessionGroups 15）。
- 编译：`:app:assembleDebug -PdevInstall` BUILD SUCCESSFUL。
- 真机（dev 包 `com.zl.ruyiai.yuan.dev` 0.1.52，装于 `33325df` / REDMI K80 Pro）：
  - 第四档「按群」出现；
  - 群头「科学发展史五学科讨论 · 6 人，6 条会话」，6 名成员收拢成一组并缩进；
  - 「今天，13 条会话」独立按天块，私聊（天文学代表/周建/desktop 等）不受影响；
  - 点击群头可折叠 6 条成员，组头与「今天」组保留。

## 影响与回滚
- 仅改 ruyuan 工作树 `wt-folder-model`（分支 `folder-model`），零协议改动，不碰 relay / host / 正式版。
- 回滚：删除 `GroupBlock` 与 `GROUP` 分支、UI 还原三档即可；维度落盘数据向后兼容，无需迁移。

## 状态
done（代码 + 单测 + 编译 + 真机验证均通过；**未提交、未推送**，等用户最终验收后单独授权提交）。
