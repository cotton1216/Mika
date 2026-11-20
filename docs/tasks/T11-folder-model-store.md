# T11 · Remote 会话文件夹模型与本地持久化（数据层）

## 目标与验收标准
- 目标：在 ruyuan（`mika/Mika`）Remote 模块建立与 dsh-Go 对齐的「文件夹模型 + 本地持久化」数据层，为后续会话列表分组打基础。
- 验收：
  1. `RemoteFolder` 六字段全对齐 dsh-Go `DrawerFolder`（id/name/tint/emoji/collapsed/sessionIds）；
  2. `FolderStore` 按「中继 + 主机」namespace 隔离，落 SharedPreferences；
  3. 单测覆盖序列化 round-trip、namespace 隔离、CRUD、sessionIds 去重，全部通过；
  4. `:ruiyi-remote-ui:testDebugUnitTest` 与 `assembleDebug` 通过；
  5. 既有代码零改动（本期不接 UI）。

## 范围
- 本期做：`session/` 包内新增 `RemoteFolder`（模型 + JSON 序列化）、`FolderStore`（接口 + SharedPrefs + InMemory 实现）、`FolderView`（folders + sort 顶层容器）、单测。
- 本期不做：UI 分组渲染、折叠动画、置顶（dsh-Go 靠 workspace pins，ruyuan 无此概念）、跨端同步、接入 SessionDrawer（下一阶段任务）。

## 方案要点
- 技术选型：Kotlin `data class` + kotlinx.serialization（参照 `SessionSnapshotStore.kt` L83–116）+ SharedPreferences（参照 L63–78）。
- isolation：复用 `sessionSnapshotNamespace(serverUrl, hostId)` 生成 namespace，但用独立 prefs 文件名 `dsh_remote_folder`，避免与会话快照同键冲突。
- `FolderView`：顶层容器持有 `folders: List<RemoteFolder>` 与 `sort`（activity/created），提供 `folderOf` 派生映射（sessionId → folderId），对齐 dsh-Go `DrawerView`。
- 数据契约：一个会话至多属于一个文件夹（写操作保证从其他文件夹移除）。

## 阻塞/依赖
- 无外部依赖；仅依赖现有 `SessionSummary`（`session/SessionChat.kt` L31）与 `sessionSnapshotNamespace`（`session/SessionSnapshotStore.kt` L35）。

## 影响评估
- 仅新增文件，改动隔离在 `ruiyi-remote-ui/src/main/kotlin/.../session/` 与 `src/test/`，既有代码零改动，低风险。
- 数据层先行，UI 无感知，不影响真机现有行为。

## 回滚方案
- 触发条件：编译失败或单测不过且无法快速修复。
- 动作：删除本次新增的 4 个文件（RemoteFolder.kt、FolderStore.kt、FolderView.kt、对应单测），工作树回到 `d429773b` 干净态；删除分支 `folder-model` 与工作树（需用户确认）。

## 责任人
- 实现：AI；验收：用户。

## 更新时间
- 2026-11-19（方案 v0.3 确认后建档）

## 进度快照
- 状态：待验收（实现 + 验证均完成，未提交）。
- 已完成：方案共识（v0.3）、工作树创建、任务文档与记忆目录建档；数据层四文件落盘：
  - `RemoteFolder.kt`（六字段模型 + JSON 编解码 + 成员去重）
  - `FolderView.kt`（顶层容器 + folderOf 派生 + assign/unassign 唯一归属写操作 + 排序枚举）
  - `FolderStore.kt`（接口 + InMemory + SharedPrefs 实现，独立 prefs 文件 `dsh_remote_folder`）
  - `FolderStoreTest.kt`（5 单测：round-trip / 去重 / 唯一归属 / namespace 隔离 / 坏 JSON 兜底）
- 验证证据：`:ruiyi-remote-ui:testDebugUnitTest` → `FolderStoreTest` 5/5 通过（failures=0, errors=0）；`:ruiyi-remote-ui:assembleDebug` → BUILD SUCCESSFUL。
- 构建期间修复：`RemoteFolder.kt` KDoc 中 `session/*` 的 `/*` 被词法器当成嵌套注释起始致 KDoc 未闭合，已改措辞消除；此为本轮唯一编译错误，修复后全绿。
- 已否决：置顶（ruyuan 无 workspace pins 概念）、跨端同步（dsh-Go 亦为 per-host 本地）。
- 未验证假设：SharedPrefsFolderStore 真机落盘（本期仅单测覆盖 InMemory + 编解码，真机 I/O 未跑）。
- 下一步：用户验收 → 授权提交（当前未提交、未推送）；下一阶段任务＝接 UI 分组渲染。
