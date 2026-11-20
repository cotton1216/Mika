# T13 · Remote 会话分组维度可切换（文件夹常驻顶层）

> 承接 T12「文件夹 + 按天」固定分组。方案快照 v0.4 经用户确认（2026-11-19 16:46）：维度可切换 + 文件夹常驻顶层 + 本期开放天/状态/目录三维。任务名沿用 `remote-list-grouping`，不新建任务记忆目录。

## 目标与验收标准
- 目标：会话抽屉顶部提供分组维度切换（按天 / 按状态 / 按目录），文件夹块在所有维度下常驻顶层，未入文件夹的会话按所选维度分组。
- 验收：
  1. `groupSessions` 增加 `dimension` 参数（默认 DAY，保持旧调用兼容），输出 `FolderBlock` 恒定在前 + 维度块（Day/Status/Cwd）；
  2. 契约保持：每条会话恰好出现一次（文件夹优先，其余按所选维度）；
  3. 按状态：`running` 分「进行中/已结束」两块；按目录：按 `cwd` 末段目录名分组，`cwd` 空归「未指定目录」；
  4. 维度选择按「中继 + 主机」namespace 落 `SharedPreferences`（新 key 前缀 `dsh_remote_group_dimension`），切主机重置读取；
  5. 组头全部可折叠：文件夹沿用 `RemoteFolder.collapsed`（落盘），维度块折叠为本地 `Set<String>`（按块 key，切维度/切主机重置、不落盘）；
  6. 单测覆盖三维度分桶、文件夹常驻、唯一出现、维度切换稳定性；`:ruiyi-remote-ui:testDebugUnitTest` 与 `:app:assembleDebug -PdevInstall` 通过；
  7. 既有行为（新建/刷新/选中/切换/后台任务入口/骨架/错误态）零回归。

## 范围
- 本期做：`session/SessionGroups.kt`（维度枚举 + Status/Cwd Block + 分组参数）；`session/GroupDimensionStore.kt`（新持久化）；`ui/screen/SessionDrawer.kt`（维度切换控件 + 新 Block 分发 + 折叠泛化）；`ui/AppNav.kt`（维度状态注入 + 落盘）；`test/.../SessionGroupsTest.kt` 新增单测。
- 本期不做：按来源维度（origin 取值未枚举，列为待确认，二期抓包后补）、按创建时间（无 createdAt 字段，技术不可实现）、按主机（协议无 host 字段）、文件夹管理 UI、任何提交/推送。

## 方案要点
- `enum class GroupDimension { DAY, STATUS, CWD }`；`GroupStatus { RUNNING, ENDED }`。
- `SessionBlock` 新增 `StatusBlock(status, items)` / `CwdBlock(label, items)`；文件夹块生成逻辑原样保留、恒定在最前。
- `groupSessions(sessions, view, now, dimension = DAY)`：先装文件夹，剩余会话按 dimension 分桶；DAY 维持 T12 四桶，STATUS 二分，CWD 按目录名归并。排序键仍为 `updatedAt` 倒序 + `id` tie-break。
- 折叠 state 泛化为 `collapsedKeys: Set<String>`，块 key 形如 `day-TODAY` / `status-RUNNING` / `cwd-<目录名>`；文件夹折叠仍走 `RemoteFolder.collapsed`。
- 持久化：`GroupDimensionStore` 接口（真机 SharedPrefs + 单测 InMemory），namespace 口径复用 `sessionSnapshotNamespace`。

## 阻塞/依赖
- 依赖 T11/T12 已落盘数据层与渲染层。
- dsh-Go 参考：`drawer_view_state.dart` L621–723（drawerBlocks）、`jobs_page.dart`（live/ended 二分，STATUS 维度语义来源）。

## 影响评估
- 改动 5 个源文件 + T13 文档 + TASKS.md；T11/T12 既有数据结构不破坏（`groupSessions` 默认值兼容旧 6 单测）。
- 维度选择落新 key，与 `dsh_remote_folder` 并存，不动旧数据；dev 包覆盖不影响正式版。
- 切维度后维度块折叠态重置——与现有切主机重置一致，属可接受取舍（已在 v0.4 快照列为「被接受的风险」）。

## 回滚方案
- 触发条件：编译失败/单测不过且无法快速修复，或真机出现明显回归。
- 动作：撤销 T13 五个源文件改动 + 删除 GroupDimensionStore.kt 与新增单测，工作树回到 T12 完成态；真机回装上一版 dev APK。T13 未提交，回滚不涉及远端。

## 责任人
- 实现：AI；验收：binny。

## 更新时间
- 2026-11-19（T13 建档，方案 v0.4 经用户确认）

## 进度快照
- 状态：待用户验收（实现 + 编译 + 单测 + 真机安装均完成，未提交、未推送）。
- 已完成改动：
  - 新增 `session/GroupDimensionStore.kt`：`GroupDimensionStore` 接口 + `InMemory`（默认 DAY）+ `SharedPrefs`（prefs 文件 `dsh_remote_group_dimension`，namespace 隔离，非法值兜底 DAY）；
  - `session/SessionGroups.kt`：新增 `GroupDimension`(DAY/STATUS/CWD)、`GroupStatus`(RUNNING/ENDED)、`StatusBlock`/`CwdBlock`、`cwdLabel`（同时切 `/` 与 `\`）；`groupSessions` 加 `dimension`（默认 DAY 兼容 T12），文件夹块恒定在前，剩余按维度分桶；
  - `ui/screen/SessionDrawer.kt`：加 `dimension`/`onSelectDimension` 参数；新增 `DimensionSwitcher`（三档胶囊）；折叠态从 `collapsedDays:Set<SessionDay>` 泛化为 `collapsedKeys:Set<String>`（`remember(hostKey, dimension)`）；LazyColumn 分发新增 Status/Cwd 分支；新增 `statusLabel`/`toggleKey`；
  - `ui/AppNav.kt`：建 `GroupDimensionStore` 状态，按 `snapshotNamespace` 读取、切主机换维度；`selectDimension` 回调乐观更新 + 协程写回；传参 `SessionDrawer`；
  - `test/.../SessionGroupsTest.kt`：新增 5 单测（默认 DAY 兼容、STATUS 二分顺序、CWD 目录归并+未指定目录、文件夹常驻+唯一出现、维度切换稳定性）；
  - 文档：`docs/tasks/T13-group-dimensions.md` 建档、`TASKS.md` 新增 T13 行。
- 验证证据：`:ruiyi-remote-ui:testDebugUnitTest` → 16/16（FolderStore 5 + SessionGroups 11，failures=0/errors=0）；`:app:assembleDebug -PdevInstall` → BUILD SUCCESSFUL；APK `com.zl.ruyiai.yuan.dev` 0.1.52「如意 AI Dev」。
- 真机：经用户确认后安装到 `33325df`（Redmi 24122RKC7C / REDMI K80 Pro，adb 5038 端口），`Performing Streamed Install → Success`；dev 包版本 0.1.52 已更新，正式版 `com.zl.ruyiai.yuan` 完整保留、两包共存。
- 未验证假设：维度切换的真机观感与折叠交互（待用户复验）；SharedPrefs 维度落盘真机 I/O（单测覆盖接口与默认值，真机未逐档读回）。
- 构建注意：本机需注入 `ANDROID_HOME=/Users/binny/Library/Android/sdk`（工作树无 local.properties）；三件套环境变量 + dangerouslyDisableSandbox 必备。
- 下一步：用户真机复验三维切换 → 验收通过后按授权决定是否提交（当前未提交、未推送）。
