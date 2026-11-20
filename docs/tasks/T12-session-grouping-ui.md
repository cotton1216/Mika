# T12 · Remote 会话列表分组渲染（展示层，接 T11 数据层）

## 目标与验收标准
- 目标：在 ruyuan（`mika/Mika`）Remote 会话抽屉里，把 T11 建好的 `FolderView`/`FolderStore` 接入 `SessionDrawer`，实现会话列表按「自定义文件夹 + 按天」分组渲染，复刻 dsh-Go `drawerBlocks` 的展示形态。
- 验收：
  1. 纯函数分组算法对齐 dsh-Go `drawerBlocks`（本期裁掉 pinned/filter 两块：ruyuan 无 workspace pins、无状态过滤入口）；输出 `FolderBlock`/`DayBlock`；
  2. 每个会话恰好出现一次：有文件夹归文件夹、无文件夹按天（今天/昨天/本周/更早）；
  3. 分天按本地日历、周一为一周起点（对齐 dsh-Go `sessionDayOf`）；
  4. 文件夹组头可点折叠/展开，状态写回 `FolderStore`（per「中继 + 主机」namespace 持久化）；
  5. 单测覆盖分天边界、归属唯一、排序、空文件夹；`:ruiyi-remote-ui:testDebugUnitTest` 与 `assembleDebug` 通过；
  6. 既有行为（新建/刷新/选中/切换/后台任务入口/骨架/错误态）零回归。

## 范围
- 本期做：`session/SessionGroups.kt` 分组纯函数 + 单测；`SessionDrawer.kt` 渲染分组标题与折叠交互；`AppNav.kt` 注入 `FolderView`/`FolderStore`（复用 `sessionSnapshotNamespace` 与快照同一 host 隔离口径）。
- 本期不做：文件夹管理 UI（新建/重命名/删文件夹、会话移入移出——数据层 `assign`/`unassign` 已具备能力，但无界面）、置顶（ruyuan 无 pins）、状态过滤、跨端同步、真机调试（按用户决定，先不接真机）。

## 方案要点
- 分组算法 `groupSessions(sessions, view, now)`：
  - 排序键 = `updatedAt`（`FolderSort.CREATED` 本期因无 createdAt 数据源，退化为 `updatedAt`，与 dsh-Go `keyOf` 缺省一致）；倒序 + sessionId 做 tie-break；
  - 先按 `view.folderOf` 把成员装入文件夹块（沿用 `view.folders` 顺序，空文件夹也出块以便后续管理入口可见）；
  - 剩余会话按 `sessionDayOf(updatedAt, now)` 装入今天/昨天/本周/更早四块，块顺序固定；
  - `now` 作为参数注入，便于单测钉死时钟。
- UI：沿用 `LazyColumn`，用 `item` 出组头（`stickyHeader` 需 `LazyColumn` 作用域，本期用普通 heading 行即可），`items` 出成员；组头文案复用 dsh-Go 语义（今天/昨天/本周/更早 + 文件夹名 + 条数）；折叠沿用 `RemoteFolder.collapsed`。
- 折叠写回：`AppNav` 持有 `FolderView` 状态（按 `snapshotNamespace` Reset），点组头回调里 `view.copy(...)` 后 `folderStore.write`；组件不直接碰 Store。
- 复用 T11 的 `FolderView.folderOf`、`byId`、`RemoteFolder` 六字段与 `FolderStore` 接口，不重复建模。

## 阻塞/依赖
- 依赖 T11 数据层四文件（已落盘、已验证）。
- dsh-Go 参考：`drawer_view_state.dart` L621–723（`DrawerBlock`/`drawerBlocks`）、L457–477（`sessionDayOf`）、`drawer_list.dart` L115–158（按 block 渲染）、`folder_header.dart`（组头样式）。

## 影响评估
- 改动集中在 `SessionGroups.kt`（新增）、`SessionDrawer.kt`、`AppNav.kt`、`TASKS.md`/T12 文档；既有数据层不动。
- 会话列表默认无文件夹（T11 Store 初始为空），因此新用户看到的仍是按天分组——比原先「一整条平铺」更可读；老数据无文件夹成员，无迁移问题。
- 切主机时 `snapshotNamespace` 变化触发 `FolderView` 重置读取，杜绝串机显示旧主机文件夹。

## 回滚方案
- 触发条件：编译失败/单测不过且无法快速修复，或 UI 分组出现明显回归。
- 动作：撤销 `SessionDrawer.kt`/`AppNav.kt` 的分组接线、删除 `SessionGroups.kt` 及对应单测；T11 数据层保留（工作树回到 T11 完成态）。

## 责任人
- 实现：AI；验收：用户。

## 更新时间
- 2026-11-19（T12 建档，范围经用户确认：只做展示、不做管理 UI、不接真机）

## 进度快照
- 状态：待验收（实现 + 验证均完成，未提交、未推送、未接真机）。
- 已完成改动：
  - 新增 `session/SessionGroups.kt`：`SessionBlock`（Folder/Day）+ `SessionDay` + `sessionDayOf` + `groupSessions` 纯函数；
  - 新增 `test/.../SessionGroupsTest.kt`：6 单测（分天四桶 + 未来归今天、归属唯一、块顺序、排序 tie-break、空文件夹、无文件夹纯分天）；
  - `ui/screen/SessionDrawer.kt`：`SessionDrawer`/`SessionsTab` 加 `folderView`+`onToggleFolder`，平铺渲染改为按 block 出组头与成员；文件夹组头可折叠、成员缩进一档；新增 `FolderHeaderRow`/`GroupEyebrow`/`dayLabel`/8 色 tint 板；
  - `ui/AppNav.kt`：建 `FolderStore`+`FolderView` 状态，按 `snapshotNamespace` 读取（切主机换视图），折叠乐观更新 + 协程写回；新参数注入 `SessionDrawer`。
  - 建档：`docs/tasks/T12-session-grouping-ui.md`、`TASKS.md` 新增 T12 行。
- 验证证据：`:ruiyi-remote-ui:testDebugUnitTest` → `FolderStoreTest` 5/5 + `SessionGroupsTest` 6/6，共 11/11（failures=0, errors=0）；`:ruiyi-remote-ui:assembleDebug` → BUILD SUCCESSFUL。
- 构建期间修复：`SessionRow` 的 `Modifier.padding(start/end/vertical)` 混用命名参数与 `vertical` 不合法，改为四边分别命名；这是本轮唯一编译错误，修复后全绿。
- 已按用户决定排除：文件夹管理 UI（新建/重命名/移动）、置顶、状态过滤、真机调试。
- 未验证假设：`SharedPrefsFolderStore` 真机落盘、分组 UI 真机观感（本期不接真机；单测覆盖纯函数与编解码）。
- 下一步：用户验收 → 授权提交（当前未提交、未推送）。

### 补充（真机验收反馈，2026-11-19 16:39）
- 反馈：真机上按天分组生效，但按天组头是纯文本 `GroupEyebrow`、不可折叠（只有文件夹组头可折叠）；用户要求分组可折叠。
- 处理：`SessionsTab` 新增本地折叠态 `collapsedDays: Set<SessionDay>`（`remember(hostKey)`，切电脑重置、不落盘——按天桶是动态的，折叠是一次性查看行为，避免污染新一天）；`DayBlock` 渲染改为可点击 `DayHeaderRow`（标题+条数+箭头，与 `FolderHeaderRow` 同构），折叠时隐藏该天成员。删除已无引用的 `GroupEyebrow`。
- 验证：`testDebugUnitTest` + `:app:assembleDebug -PdevInstall` 全绿；dev APK 覆盖安装到真机 `33325df`（Success），正式版未动。
- 真机状态：已安装并排 dev 包「如意 AI Dev」（`com.zl.ruyiai.yuan.dev`，0.1.52），按天分组现可折叠，待用户复验。
