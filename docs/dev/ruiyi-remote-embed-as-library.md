# 如意远程（ruiyi-remote-native）拆分为「双 App + 共享库」并嵌入 Mika 方案

> 日期：2026-11-15 ｜ 状态：**实施中：§2–§3 已完成，米卡 `:app-main` 接入已打通并编译通过** ｜ 性质：工程改造设计
> 源仓：`ruiyi-remote-native`（独立 git 仓，当前单 `:app` 模块）
> 宿仓：`mika/Mika`（`com.wangzan101.mika`，多模块 `include(":lib-core", ":app-main")`）
> 目标：把已跑通的如意远程原生实现作为 `remote-core` / `remote-ui` 两个库模块嵌入 Mika，
> 以「右侧抽屉＝远程控制、左侧＝米卡原功能」呈现，且不影响 Mika 与上游 `origin/main` 的合并。

---

## 0. 结论先行

1. **方向锁定**：Mika 原有功能（含上游自带 `core/.../core/remote/` 那套任务/Relay 体系）**完整保留、不做任何改动**；新增 `remote-core` + `remote-ui` 两个**独立 Gradle 库模块**承载如意远程（ruiyi-remote-native），通过「抽屉入口 + 右侧页面」接入，靠**独立包名 + 独立目录**与上游隔离。
2. **🔴 核心边界**：Mika 上游 `origin/main` 里**已存在**一套 `core/src/main/kotlin/com/wangzan101/mika/core/remote/`（12 个文件：RemoteAccess、RelayClient、RelayGateway、RemoteTaskApi…）。那是**米卡原生任务远程**，与要嵌的**如意远程（ruiyi-remote-native）是两套完全不同的东西**。**新模块绝不复用 `core/remote/` 目录或包名**，否则上游一更新必然冲突。
3. **不破坏合并**：本次拆分成 `remote-core/`、`remote-ui/` 两个**全新自包含目录**，只新增不改动任何上游已有文件，上游合并走三路快进、几乎零冲突。
4. **可行性已核验**：`ruiyi-remote-native` 的 `channel/protocol/crypto/net/transport/util/auth/account/keepalive/links` 十个底层包共 **28 个文件、零 Android 引用**（grep `androidx.|android.|Activity|Context` 全为 0），是干净的纯 Kotlin core；`session` 10 个 + `ui` 64 个依赖 Compose/Activity，归入 ui 层。

---

## 1. 现状（勘察事实）

### 1.1 源仓 `ruiyi-remote-native`

- 顶层：`app` 单模块；`settings.gradle.kts` = `include(":app")`。
- 包：`io.github.ccch1mneyyy.ruiyiremote.*`
- 根 Composable：`RemoteHome(pendingCallback, onCallbackConsumed)`（`MainActivity.kt:24`）
- OIDC 回跳：`LinkTrampolineActivity`（`links/`），scheme 如 `ruyiai://auth3/callback`

| 包 | 文件数 | Android 引用 | 归属 |
| --- | --- | --- | --- |
| `channel` | 5 | 0 | core |
| `protocol` | 4 | 0 | core |
| `crypto` | 4 | 0 | core |
| `transport` | 4 | 0 | core |
| `auth` | 3 | 0 | core |
| `account` | 3 | 0 | core |
| `links` | 2 | 0 | core |
| `net` | 1 | 0 | core |
| `util` | 1 | 0 | core |
| `keepalive` | 1 | 0 | core |
| **core 合计** | **28** | **0** | — |
| `session` | 10 | 有（Compose/Activity） | ui |
| `ui`（screen/components/theme） | 64 | 有（Compose + Material3） | ui |
| **ui 合计** | **74** | — | — |

依赖：okhttp、bouncycastle（lightweight）、kotlinx.serialization.json、kotlinx.coroutines(android)、compose-bom/material3/animation。

### 1.2 宿仓 `mika/Mika`

- 顶层 `settings.gradle.kts`：`include(":lib-core", ":app-main")`；`placement FAIL_ON_PROJECT_REPOS`。
- `app-main`：`namespace com.wangzan101.mika`，minSdk 30，compileSdk 36，Compose。
- 导航：`MikaRoot`（`MainActivity.kt:356`）用 `Route` 栈（非 NavHost），各页面只经回调进出。
- 左侧抽屉：`ChatWithDrawer` + `ModalNavigationDrawer` + `rememberDrawerState`（`ChatDrawer.kt`）。
- **上游自带 remote**：`lib-core/.../core/remote/` 12 文件（不同体系，勿动）。
- git remotes：`origin`=wangzan101/Mika、`me`=cotton1216/Mika、`xxt`=内网如愿线；**无 submodule、无 `.gitmodules`**。

---

## 2. 模块结构（双 Application + 共享库）

**用户决策（2026-11-15）**：除了把远程拆成 core/ui 库，还要求在 Mika 仓内**保留如意远程作为独立的 Application 模块**（可独立发 APK），同时 Mika 主 App 引用其界面库。结果：`:app-main` / `:app-ruyi-remote` 两个可运行入口，共享同一套远程库。

> **实施中发现的分层细化（2026-11-15 实测依赖图后修正）**：不是所有底层包都在 core。`keepalive/RemoteKeepAliveService`（Android 前台 Service）被 ui 层 `SettingsScreen` 静态调用 → 归 **ui 库**；`MainActivity`、`links/LinkTrampolineActivity` 只被壳引用 → 归 **App 壳**；`links/DeepLinks` 被 ui 用 → 归 **ui**。`RemoteHome` 原用 `context as? MainActivity` 拿 `openSignInBrowser` → 解耦为参数注入。

```
mika/Mika/
├─ settings.gradle.kts          # include(":lib-core", ":app-main", ":lib-ruyi-remote-core",
│                               #         ":lib-ruyi-remote-ui", ":app-ruyi-remote")
├─ lib-core/                    # 米卡逻辑库（原有，不动）
├─ app-main/                     # 米卡主 App（原有，加一行依赖）
│   └─ dependencies: implementation(project(":lib-ruyi-remote-ui"))
├─ lib-ruyi-remote-core/           # com.android.library，远程纯逻辑（新增）
│   └─ src/main/java/…/ruiyiremote/
│       channel · protocol · crypto · transport · net · util
│       auth · account · keepalive · links         （28 文件，零 Android）
├─ lib-ruyi-remote-ui/             # com.android.library + compose，远程界面（新增)
│   └─ src/main/java/…/ruiyiremote/
│       session · ui(screen/components/theme)      （74 文件，含 RemoteHome）
└─ app-ruyi-remote/                # com.android.application，如意远程独立 App 壳（新增）
    └─ AndroidManifest(LAUNCHER/KeepAlive/OIDC scheme) + applicationId
```

**依赖方向（单向）：**
```
:lib-ruyi-remote-ui   → :lib-ruyi-remote-core
:app-ruyi-remote      → :lib-ruyi-remote-ui（独立 App 入口＋壳）
:app-main             → :lib-ruyi-remote-ui（右侧抽屉 embedded RemoteHome，不带壳）
```

**两个可运行入口的分工：**
| | `:app-main`（米卡） | `:app-ruyi-remote`（如意远程） |
| --- | --- | --- |
| 角色 | 主 App，Remote 作为右侧抽屉功能 | 独立 App，走如意远程自家交互 |
| 启动 | `MainActivity`（米卡全新） | ruyi-remote 的 `MainActivity`（LAUNCHER） |
| 保活 | 不引入（Remote 功能随米卡生命周期） | `RemoteKeepAliveService`（前台服务） |
| OIDC scheme | `ruyiai://`（需与 home 协调，避免双 App 抢） | `ruyiai://auth2|auth3/callback` |
| 依赖库 | `:lib-ruyi-remote-ui` | `:lib-ruyi-remote-ui` + `:lib-ruyi-remote-core` |
| 产物 | 米卡 APK（含 Remote 功能） | ruyi-remote 独立 APK（对应现网 1.0.9 通道） |

**拆三层的原因**
- `lib-ruyi-remote-core` 纯 Kotlin 库：可被任一宿主引用，无 App/Compose 耦合。
- `lib-ruyi-remote-ui` 界面壳：`session` + Compose，`RemoteHome` 根组件对 `:app-main` 与 `:app-ruyi-remote` 都能用。
- `app-ruyi-remote`（application 壳）：承载「独立可运行」的保留诉求——LAUNCHER/KeepAlive/OIDC 回跳只住壳层，库层不背。这样 Mika 主 `:app-main` 引用库时**不会**带上远程的启动页/保活服务，无抢占冲突；如意远程仍能单独发版。

**包名策略（不冲突的根）**
- 三库/壳统一 `com.xxt.zl.ai.ruyiremote`（用户定的自有品牌，替代源仓 `io.github.ccch1mneyyy.ruiyiremote`）。
- 不复用上游 `com.wangzan101.mika.core.remote`。
- 各自 `namespace`：core/ui/remote-app 同前缀但属不同 Gradle 模块，链接期由依赖决定类归属。
- 与 Mika 自我命名完全隔离 ⇒ 三路合并不会把新文件当上游冲突。

---

## 3. 嵌入与接入（右侧抽屉 / 入口）

### 3.1 承载形态：抽屉入口 + 右侧页面（米卡 App 内）

- **不重构导航结构**：Mika `MikaRoot` 的 `Route` 栈是自研导航，最省侵入的方式是**新增一个 `Route.Remote`**。
- 在左侧抽屉 `ChatWithDrawer` 底部区加「远程控制」入口 → `onRemote = { nav.push(Route.Remote) }`。
- `Route.Remote -> RemoteScreen(...)`：内容 = `RemoteHome(pendingCallback, onCallbackConsumed)`（源仓根 Composable，位于 `:lib-ruyi-remote-ui`）。

> Material3 `ModalNavigationDrawer` 原生只支持单侧抽屉，真正的「右滑抽屉」需自封装状态或引入第三方，首期成本高、易与左侧手势冲突。**推荐先用「左侧抽屉起点 + 右侧全屏页面」**（视觉上即"右侧功能"），把 gate logic 留在 `Route.Remote` 内，将来要换成真右抽屉只改这一处承载，不动 core。

> `:app-main` 只依赖 `:lib-ruyi-remote-ui`，**不带** `:app-ruyi-remote`（App 壳）里的 LAUNCHER/KeepAlive 服务，避免与米卡自身功能冲突；如意远程的独立入口由 `:app-ruyi-remote` 单独提供。

### 3.2 认证与生命周期接入点

| 事项 | 处理 |
| --- | --- |
| 登录/OIDC 回跳 | `RemoteScreen` 内部复用 `RemoteHome`；`LinkTrampolineActivity` 需在 Mika `AndroidManifest.xml` 注册（scheme/intent-filter 迁移过来） |
| 会话状态 | 远程自带 `account`+`session`，**不侵入** Mika 的 `MikaApp.chat` |
| 常驻/通知 | 若如意远程有前台服务/通知需求，按 Mika 现有 `TaskService`/`PairingNotifier` 的 channel 习惯迁移 |
| 资源隔离 | 两模块独立 `namespace` + 独立资源前缀（`remote_`），不与 `com.wangzan101.mika` 冲突 |

### 3.3 编译配置要点

- `remote-core`：`plugins { android-library; kotlin.android; kotlin.serialization }`，`namespace=io.github…ruiyiremote`，`minSdk=29`。
- `remote-ui`：`plugins { android-library; kotlin.android; kotlin.compose; kotlin.serialization }`，依赖 `:remote-core` + compose-bom/material3/animation/lifecycle。
- `app` 增加 `implementation(project(":remote-ui"))`。
- `settings.gradle.kts`：`include(":remote-core", ":remote-ui")`。

---

## 4. 不影响上游合并的依据

1. **无 submodule**：两仓各自独立；Mika `origin` 更新走既有合并（`me` 分支已在做 `merge/ruyi-upstream`）。
2. **只增不改**：所有改动 = 新增 `remote-core/`、`remote-ui/` 目录 + `settings.gradle.kts` 一行 include + 极少 `app` 接入行。上游所有已有文件**一个不改**。
3. **包名错开**：新模块保留 `io.github…ruiyiremote`，不碰上游 `com.wangzan101.mika.core.remote`。git 按路径+内容识别，两套 `remote` 互不为冲突。
4. **合并体验**：三路合并把两个新目录当「新增文件」处理，几乎零 diff；即使上游动了 `settings.gradle.kts`，冲突也只是 include 行合并，秒解。

> ⚠️ 唯一会「墨迹」的坏路：把新代码塞进上游已有的 `core/remote/` 目录或复用其包名。**本方案明确排除。**

---

## 5. 实施步骤（确认后执行）

| # | 动作 | 产出 |
| --- | --- | --- |
| 1 | `settings.gradle.kts` 加 `include(":lib-ruyi-remote-core", ":lib-ruyi-remote-ui", ":app-ruyi-remote")` | 模块注册 |
| 2 | 建 `lib-ruyi-remote-core/`（com.android.library） | core 库骨架 |
| 3 | 拷 `channel/protocol/crypto/transport/net/util/auth/account/keepalive/links` 进 core，前缀替换包名到 `com.xxt.zl.ai.ruyiremote` | core 库源码 |
| 4 | 建 `lib-ruyi-remote-ui/`（com.android.library + compose） | ui 库骨架 |
| 5 | 拷 `session` + `ui` 进 ui，前缀替换包名；`RemoteHome` 对 `:app-main` 与 `:app-ruyi-remote` 都可组合 | ui 库源码 |
| 6 | 建 `app-ruyi-remote/`（com.android.application，App 壳） | 独立 APK 骨架 |
| 7 | 壳里放 `MainActivity`(LAUNCHER)、`RemoteKeepAliveService`、`LinkTrampolineActivity` + OIDC scheme；`BuildConfig` 的 `RUYI_LOGIN_ORIGIN`/`RELAY_SERVER_URL` 构建期可注入 | 如意远程独立 App 可跑 |
| 8 | `:app-main` 加 `implementation(project(":lib-ruyi-remote-ui"))`；`MikaRoot` 加 `Route.Remote`；`ChatWithDrawer` 加「远程控制」入口 → `nav.push(Route.Remote)`；`Route.Remote` 内容=`RemoteHome` | 米卡入口接通 |
| 9 | 编译 `:app-main:compileDebugKotlin`、`:app-ruyi-remote:assembleDebug`；跑 core 的 JVM 单测（`unitTests.isReturnDefaultValues=true` 已具备） | 绿 |
| 10 | 真机验收：米卡左侧功能如常 + 右侧远程可登录连桌面；如意远程独立 App 单独可发版 | 通过 |
| 11 | 提交（`me` 分支）；上游后续更新走既有合并流程 | 可追溯 |

**验收判据**
- `:app-main:compileDebugKotlin BUILD SUCCESSFUL`、`:app-ruyi-remote:assembleDebug` 出 APK
- core JVM 单测绿（crypto/protocol/channel 属纯 JVM，无需设备）
- 真机：米卡原有抽屉/会话/任务零回归；如意远程经右入口登录→连线→读远端会话；独立 App 也能装
- `git status` 仅见 `lib-ruyi-remote-core/`、`lib-ruyi-remote-ui/`、`app-ruyi-remote/`、`settings.gradle.kts`、`app-main` 少量接入行

---

## 6. 已定决策（2026-11-15 用户确认）

| # | 事项 | 拍板 | 理由 |
| --- | --- | --- | --- |
| 1 | 承载形态 | **抽屉入口 + 右侧全屏页面**（`Route.Remote` → `RemoteHome`） | ModalNavigationDrawer 单侧；真右抽屉成本高、手势冲突；首期 route 承载、将来可升级 |
| 2 | 包名 | **改自有品牌 `com.xxt.zl.ai.ruyiremote`**（源仓 `io.github.ccch1mneyyy.ruiyiremote`，拷入时重命名，不复刻到 Mika 树） | 已脱离原仓主，包名用自有命名空间；新包名只出现在拷入后的 Mika 模块，三方（上游 core/remote、源仓 io.github、Mika）完全隔离 |
| 3 | 模块归属 | **落 Mika 仓内，双 App + 共享库**：`:lib-ruyi-remote-core` + `:lib-ruyi-remote-ui` + `:app-ruyi-remote`（独立 App 壳），`:app-main` 引 `:lib-ruyi-remote-ui` | 如意远程既能独立发 APK，又能作为功能嵌入米卡；同源打包、合并可追溯；composite build 复杂度首期不值 |
| 4 | 上游 `core/remote/` 边界 | **两套 remote 完全并行、各自独立，新模块绝不触碰上游同名目录/包名** | 隔离是「合并不冲突」的根 |

**包名重命名实施要点**（拷入时执行，源仓不动）：
- 目录：`io/github/ccch1mneyyy/ruiyiremote/` → `com/xxt/zl/ai/ruyiremote/`
- 字符串替换：`io.github.ccch1mneyyy.ruiyiremote` → `com.xxt.zl.ai.ruyiremote`（含 `package`、`namespace`、`import` 全量；**196 个唯一 import 根全部以旧前缀开头，0 例外，单一替换可完整覆盖**）
- manifest：main + debug **零改动**（全用相对类名 `.MainActivity` 等，由新 `namespace` 自动解析）
- `app-main/build.gradle.kts`：`namespace` 改新包名；库模块**删除 `applicationId`**（库无独立 App ID，App 壳模块由 `:app-ruyi-remote` 才有）
- 覆盖范围：main 64 个 .kt + test 21 个 .kt + build.gradle.kts；无 androidTest
- 编译验证：`:app-main:compileDebugKotlin` + core JVM 单测仍绿

> 待办（相对 §5 排期）：四项决策全部对齐后，进入 §5 步骤 1–2（搭模块骨架、注册 include）。

---

## 7. 证据索引

| 主题 | 路径 | 关键行 |
| --- | --- | --- |
| 如意远程根 Composable | `ruiyi-remote-native/app/src/main/kotlin/…/ruiyiremote/MainActivity.kt` | `:24`（`RemoteHome`） |
| 如意远程包结构 | `ruiyi-remote-native/app/src/main/kotlin/…/ruiyiremote/` | 16 子包 |
| 如意远程依赖 | `ruiyi-remote-native/app/build.gradle.kts` | okhttp/bc/serialization/compose |
| Mika 导航实栈 | `mika/Mika/app-main/…/ui/MainActivity.kt` | `:356`（`MikaRoot`）、`:478`（`ChatWithDrawer`） |
| Mika 左抽屉 | `mika/Mika/app-main/…/ui/ChatDrawer.kt` | `ModalNavigationDrawer` |
| Mika 模块声明 | `mika/Mika/settings.gradle.kts` | `include(":lib-core", ":app-main")` |
| **上游自带 remote（勿动）** | `mika/Mika/lib-core/.../core/remote/` | 12 文件 |
| 上游模块声明 | `git show origin/main:settings.gradle.kts` | `include(":lib-core", ":app-main")` |
| Mika remotes | `git remote -v` | origin/me/xxt，无 submodule |