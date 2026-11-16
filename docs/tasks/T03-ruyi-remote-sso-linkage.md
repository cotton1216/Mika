# T03 如愿 ↔ 如意 共享登录闭环（退出联动 + 失败可观测）

> 状态行见仓根 `TASKS.md`（T03 行）。本文件是方案与进度单一事实源。
> 版本 v1.4 · 生成 2026-11-16 10:52 · 修订 12:15（v1.3 上线模拟器验收时发现「登录成功但如意仍停登录页」根因：`RemoteAccount.restore()` 的内存缓存 `restored` 标志短路了重读；补 `reload()` + 常驻会话重读时先失效缓存，并让 `ssoState` 改为响应式）· 方案状态：实现完成，编译通过，模拟器验证通过（登录/退出双向联动 + 失败可观测）

## 一、任务标识

| 项 | 值 |
| --- | --- |
| 任务ID | T03 |
| 责任实现 | AI（WorkBuddy） |
| 决策/验收人 | binny |
| 目标项目 | `/Users/binny/workspace-sdd/mcp-demo/dsh/mika/Mika`（单项目，仅 Mika 仓） |
| 工作树 | 主工作树 `.../mika/Mika` · 分支 `ruyuan` · 起始提交 `de9102cd` |
| 边界 | **不含独立如意远程 App（`com.xxt.zl.ai.ruyiremote`）**，不含中继 `dsh-remote-upstream` 发布 |

## 二、目标与验收标准

### 目标

如愿 App 内「如愿」与「如意」两个模块的账号状态严格一致：**如愿登录即如意就位，如愿退出即如意退出，注册失败用户可感知**。

### 验收标准

1. **登录**：如愿账号登录成功后进「远程控制」，直接落在「我的电脑」，无二次登录页。
2. **退出**：在如愿里退出如意账号后进「远程控制」，必须回到登录页（配对码 / 账号密码），本机不得残留上一账号的设备令牌。
3. **失败可观测**：中继不可达（或返回非 2xx）时登录，如愿模型功能照常；「远程控制」页给出明确提示，日志含失败原因，不再静默。
4. **回归**：如愿其他服务商（OpenRouter / xAI / ChatGPT）的登录与退出行为不变；独立如意远程 App 不受影响。
5. **构建**：`assembleDebug` 通过；`ruiyi-remote-*` 与 `app` 模块相关单测通过。

## 三、现状（代码级，逐行核实）

如愿 App 是**一个包两个模块**：`app/build.gradle.kts:53` `applicationId = "com.zl.ruyiai.yuan"`（release），`:144` `implementation(project(":ruiyi-remote-ui"))`，入口 `app/.../ui/RemoteScreen.kt` 套 `RemoteHome`。

### 已实现的登录联动（无需新建）

| 步骤 | 位置 | 行为 |
| --- | --- | --- |
| 1 | `core/.../oauth/RuyiAuth.kt:182-192` | AIServer `/desktop/authorize`，`client_id=ruyi-android`，回调 `ruyiai://auth2/callback` |
| 2 | 同上 `EXCHANGE_PATH` | 如愿**自己**打 `/api/v1/auth/desktop/exchange` 换 `access_token` |
| 3 | `app/.../ui/ModelSetupViewModel.kt:871` | `signInWithRuyi(...)` 完成登录 |
| 4 | `app/.../ui/ModelSetupViewModel.kt:878` | `onRuyiSignedIn(signed.accessToken)` |
| 5 | `app/.../ui/ModelSetupScreen.kt:111` | `RemoteSso.signIn(ctx, accessToken)` |
| 6 | `ruiyi-remote-ui/.../RemoteSso.kt:38-44` | `RemoteAccount.loginWithAiServerToken` → 中继 `POST /api/v1/auth/iam` |
| 7 | 中继 `apps/server/src/server.ts:534` | `iamFromToken → iamProfile` 读同一 AIServer profile，`openSession` 签发中继会话 |
| 8 | `ruiyi-remote-core/.../ServerApi.kt:337` + `RemoteAccount.kt:332` | `POST /api/v1/devices/register` 注册本机为 client，令牌进 Keystore |
| 9 | `ruiyi-remote-ui/.../RemoteHome.kt` `restore()` | 直接进「我的电脑」 |

`app/.../ui/RemoteScreen.kt:41` 传 `showSso = false`，注释「设备在如愿登录时已经注册好，这里不再摆第二个如意账号入口」——**如愿里的如意不发起 auth3 授权**，`/api/v1/auth/aiserver/exchange`、`DSH_AISERVER_*` 在本次任务中均不参与。

### 生产实证（2026-11-16 curl）

| 探测 | 结果 | 结论 |
| --- | --- | --- |
| `GET /api/v1/auth/config` | 200，仅 `oidc` | 生产未配 `DSH_AISERVER_*` |
| `POST /api/v1/auth/iam {"token":"假"}` | 401 `AUTH_INVALID` | 路由已注册、`DSH_IDENTITY_BASE` 已配、AIServer 可达 → 登录联动生产可用 |
| `POST /api/v1/auth/aiserver/exchange` | 404 `METHOD_NOT_FOUND` | 认证归一版未发布，与本次任务无关 |

> 注意：`/api/v1/auth/iam` 的校验器是 zod `.strict()`，请求体多一个字段即 400 `INVALID_MESSAGE`，排查时勿误判为「未配置」。

## 四、问题清单

### P1 退出不联动（必做，涉及账号残留）

`app/.../settings/ProviderStore.kt:855`：

```kotlin
fun signOutRuyi(providerId: String) {
    if (provider(providerId)?.signIn == RUYI_SIGN_IN && providerId in keys) setKey(providerId, "")
}
```

只清如愿的 API Key，注释亦明写「删掉这台手机上的 Key；服务商、模型和用途留着」。**不触碰如意模块的 Keystore 设备令牌**。

而 `RemoteAccount.logout(revokeDevice: Boolean = true)`（`RemoteAccount.kt:678-687`）具备吊销设备并删本地令牌的能力，**但只有如意模块自己的设置页会调它**：

```
ruiyi-remote-ui/.../screen/SettingsScreen.kt:77      session.logout()          ← 如意设置页「退出登录」
ruiyi-remote-ui/.../RemoteSession.kt:356             runCatching { account.logout() }
```

如愿的退出路径（`ProviderStore.signOutRuyi`）**完全不经过这条链**，两者互不认识。

后果：退出如愿如意账号后进「远程控制」，仍持有上一账号的设备令牌，可继续看到并连接该账号下的电脑。换号场景属串号，单人也属残留。

### P2 注册失败静默（必做·最小版）

`app/.../ui/ModelSetupViewModel.kt:877-880`：

```kotlin
withContext(io) {
    runCatching { onRuyiSignedIn(signed.accessToken) }
        .onFailure { Log.w(TAG, "远程控制随如愿登录注册失败", it) }
}
```

注释明确「失败只记一笔——模型已经能用了，远程还能走配对码」。用户侧表现为「如愿登录成功，但进远程控制又要登录」，且无任何提示，只能靠 logcat 定位。

### 已排除（此前误判，记录以免重犯）

**relayBase 漂移：不成立。** 我曾判断 `RemoteSso.signIn` 用构建期 `DEFAULT_SERVER_URL`、而如意模块用用户配置地址，会导致注册到不同中继。实测驳回：

- `ruiyi-remote-ui/.../RemoteHome.kt:86` `relayBase = { account.serverUrl }`，而 `:80` 的 `account = RemoteAccount(KeystoreSecretStore(context))` 走默认 `DEFAULT_SERVER_URL`；
- `ruiyi-remote-ui/.../screen/SettingsScreen.kt:128` 对「中继地址」只读展示，无修改入口。

两者恒等，无漂移。此项不列入改动。

## 五、可实现性分类

| 项 | 状态 | 依据 | 原因 | 兜底（触发/动作） | 回滚（触发/动作） |
| --- | --- | --- | --- | --- | --- |
| P1 退出联动清理 | 可实现项 | `signOutAccount` 已是 `scope.launch{}`（`ModelSetupViewModel.kt:905-916`），`RemoteAccount.logout` 已存在 | 条件满足 | 若吊销请求超时：`logout` 内部已 `runCatching` 包住 revoke，本地令牌照删 | 回退 `ModelSetupViewModel.kt` / `RemoteSso.kt` 至 `de9102cd` |
| P2 失败可观测 | 可实现项 | `RemoteSso` 与 `ConnectScreen` 均在本仓 | 条件满足 | 提示写不进 UI 时降级为仅日志 + 设置页展示 | 同上 |
| 退出时同步服务端吊销 | 待确认项 | `RemoteAccount.logout(revokeDevice=true)` 会打 `api.revokeSelf` | 需网络；失败不影响本地清理 | 失败即仅删本地，服务端设备留待下次登录时 `DEVICE_REVOKED` 分支重建 | 同上 |
| 删除「服务商」条目时联动 | 可实现项（v1.3 已实现） | `removeProvider`（`ModelSetupViewModel.kt:1176`）判据是 `provider.signIn`，与退出同一回调 | 条件满足 | 注销失败仅记日志，照删本地服务商 | 删掉该分支即回到 `else -> Unit` |
| 宿主换令牌后常驻会话重读 | 可实现项（v1.3 已实现） | `RemoteHome` 在宿主 Tab 里常驻组合，`restore()` 只在冷启动跑一次 | 条件满足；`currentUser()` 纯本地判定，空值可信 | 重读失败按原 `restore()` 路径留提示 | 去掉 `LaunchedEffect(hostAccountRevision)` 即回到只冷启动读一次 |
| 如愿多 providerId 同时配如意 | 当前暂不实现项 | 现有 UI 允许同一 signIn 多个条目 | 逐个退出会重复触发 logout（幂等，无副作用） | `logout` 幂等：无令牌时直接返回 | 不涉及 |
| 独立如意远程 App 同步改动 | 技术不可实现项（本期边界外） | 属另一 App `com.xxt.zl.ai.ruyiremote` | 用户已明确本次不考虑 | 该 App 自己的登录/退出路径保持现状 | 不涉及 |
| 如意设置页退出 → 反向联动如愿 | 技术不可实现项 | `RemoteHome.kt:45` 注释「库里不反向依赖宿主」；`ruiyi-remote-ui` 不持有如愿的 ProviderStore | 库与宿主是单向依赖，反向通知需新增宿主接口，超出本期「补齐退出联动」的最小范围 | 用户在如愿侧再退出一次即可；若后续需要，可在宿主注册 `onRemoteSignedOut` 回调（需单独立任务） | 不涉及 |
| 中继侧改造 | 技术不可实现项（本期不需） | 生产 `/api/v1/auth/iam` 已可用（401 实证） | 无需新增能力 | — | 不涉及 |

## 六、实施方案

### P1 退出联动

**改动 1** `ruiyi-remote-ui/src/main/kotlin/com/xxt/zl/ai/ruyiremote/ui/RemoteSso.kt`

新增对称的退出入口，与 `signIn` 同形态：

```kotlin
/**
 * 如愿退出如意账号时调用：吊销本机设备并清掉设备令牌。
 *
 * 与 [signIn] 对称——登录联动只有一半是不完整的：不清令牌，下一个登录的人
 * 会直接继承上一个账号的电脑列表。吊销失败不影响本地清理。
 */
suspend fun signOut(
    context: Context,
    relayBase: String = DEFAULT_SERVER_URL,
    revokeDevice: Boolean = true,
) {
    val account = RemoteAccount(KeystoreSecretStore(context.applicationContext), relayBase)
    account.logout(revokeDevice)
    Log.i(TAG, "远程控制已随如愿退出注销：revoke=$revokeDevice")
}
```

**改动 2** `app/src/main/java/com/wangzan101/mika/ui/ModelSetupViewModel.kt`

- 构造参数新增（对称于 `:325` 的 `onRuyiSignedIn`）：
  ```kotlin
  private val onRuyiSignedOut: suspend () -> Unit = {},
  ```
- `signOutAccount`（`:905`）的如意分支补一行：
  ```kotlin
  p.signIn == ProviderStore.RUYI_SIGN_IN -> {
      store.signOutRuyi(providerId)
      runCatching { onRuyiSignedOut() }
          .onFailure { Log.w(TAG, "远程控制随如愿退出注销失败", it) }
  }
  ```
  > 保留 `runCatching`：注销失败不得阻断「退出如愿登录」这个用户显式动作。

**改动 3** `app/src/main/java/com/wangzan101/mika/ui/ModelSetupScreen.kt`

构造 ViewModel 处（`:111` 附近）补一个同形态回调：

```kotlin
onRuyiSignedOut = { RemoteSso.signOut(context.applicationContext) },
```

### P2 失败可观测（最小版，不改导航）

**改动 4** `RemoteSso.kt`

- 失败时把原因落到独立 SharedPreferences（`ruiyi_remote_sso`，键 `last_sign_in_failure`），成功时清除；
- 提供 `fun lastFailure(context: Context): String?` 供 UI 读取。

**改动 5** `ruiyi-remote-ui/.../screen/ConnectScreen.kt`

在 `showSso == false`（宿主嵌入场景）且当前未登录时，卡片下方渲染一行灰字提示：**「随如愿登录自动注册未成功，可在此重新登录」**，并附带简短原因分类（`unreachable` / `rejected` / `unknown`）。

> 仅展示分类，不展示服务端原文，避免把内部错误码暴露给终端用户。

### v1.3 追加（方案①：删服务商联动 + 回如愿登录入口 + 常驻会话重读）

**改动 6** `ModelSetupViewModel.kt` `removeProvider`

判据从「服务商类型」改成「登录方式」：如意来的那家只是个普通的 `Kind.OpenAiCompat`，单看类型认不出来。

```kotlin
val provider = store.provider(providerId)
when {
    provider?.signIn == ProviderStore.RUYI_SIGN_IN ->
        runCatching { onRuyiSignedOut() }
            .onFailure { Log.w(TAG, "删如意服务商时注销远程设备失败", it) }
    provider?.kind == ProviderConfig.Kind.ChatGpt -> store.signOutChatGpt(providerId)
    provider?.kind == ProviderConfig.Kind.Xai -> store.signOutAccount(providerId)
}
store.removeProvider(providerId)
```

**改动 7** `ConnectScreen` → `DshApp` → `RemoteHome` → `RemoteScreen` → `MainActivity` 逐层加 `onBackToHostSignIn`

首屏在提示下面渲染一个 `GhostButton("回如愿登录")`，默认 `null`（独立 App 与预览不显示）。如愿侧注入：

```kotlin
// MainActivity.kt:846
onBackToHostSignIn = {
    selectedTab = RootTab.Ruyuan
    nav.show(Route.RuyiLogin)   // 只切 Tab 会停在对话页，用户还得自己翻设置找登录
},
```

**改动 8** 常驻会话重读（`RemoteSso` + `RemoteSession` + `RemoteHome`）

这是方案里原本漏掉的一环，不加它「退出联动」只在重启后才生效：

- `RemoteHome` 在如愿的 Tab 里是**常驻组合**，`session.restore()` 只在 `LaunchedEffect(session)` 里跑一次；
- `RemoteSso.signIn/signOut` 用的是**另一个** `RemoteAccount` 实例，只改 Keystore，不通知常驻会话；
- 结果：退出后切回如意仍显示上一个账号的电脑列表；反过来「回如愿登录一次，这边自动恢复」也不成立。

修法两处：

1. `RemoteSso` 增加同进程信号 `revisions: StateFlow<Int>`，`signIn` 成功与 `signOut` 成功后各 `+1`（成功后才加，失败不触发重读）。宿主什么都不用传 —— 如愿与如意同包同进程；独立 App 永不调用这两个方法，计数恒 0。
2. `RemoteSession.restore()` 改成**双向**：有凭据进已登录，凭据没了且当前是 `SignedIn` 就退回未登录。清理逻辑抽成 `clearSession()`，`logout()` = `clearSession()` + `account.logout()`。

```kotlin
// RemoteHome.kt，紧接冷启动 restore 之后
val hostAccountRevision by RemoteSso.revisions.collectAsState()
LaunchedEffect(hostAccountRevision) {
    if (hostAccountRevision > 0) session.restore()
}
```

> `restore()` 的 `account.currentUser()` 是纯本地判定（读 Keystore + 过期时间），不联网，所以「返回 null」等价于「本机确实没登录」，不会因为网络抖动误踢。
> `logout()` 的 `phase = SignedOut` 从协程内挪到同步执行：界面上退出是瞬时的，`account.logout()` 仍异步做吊销。

### v1.4 追加（改动 9–11：模拟器验收揪出的真根因）

**v1.3 的 `revisions` 信号 + 双向 `restore()` 在模拟器上仍失效**：登录成功日志 `远程控制已随如愿登录注册` 后，切到如意仍停登录页。根因不在 `revisions`，而在 `RemoteAccount.restore()` 的**内存缓存**：

```kotlin
// RemoteAccount.kt
private suspend fun restore() {
    if (restored) return   // ← 第二次 restore 直接短路
    restored = true
    ...
}
```

常驻的 `RemoteHome` 里那个 `RemoteAccount` 实例在冷启动 `restore()` 时已把 `restored` 置 true、并缓存了当时的 `credentials`（登录前是 null）；`RemoteSso.signIn/signOut` 走的是**另一个** `RemoteAccount` 实例、写同一份 Keystore。于是 `revisions` 触发 `session.restore()` → `account.currentUser()` → `restore()` 被 `restored` 短路 → 永远读内存里的旧值：登录了进不去、退出了下不来。

**改动 9** `ruiyi-remote-core/.../account/RemoteAccount.kt` 新增 `reload()`：

```kotlin
suspend fun reload() {
    restored = false
    identity = null
    credentials = null
    parsedCredentials = null
    restore()   // pin 不重读（与 logout 的「pin 保留」一致）
}
```

**改动 10** `RemoteSession.restore()` 读凭据前先 `account.reload()`（不再依赖首帧缓存）。

**改动 11** `RemoteSso` 新增 `state: StateFlow<String?>` 镜像 `lastState`，`ConnectScreen` 从 `remember { lastState() }` 快照改为订阅该流 —— 否则「同一屏内注册失败/退出」写下的提示永远显示不出来（P2 的可观测在常驻组合下同样是死值）。

### v1.5 追加（改动 12–13：登录后模型变化能跟上）

**问题**：两条登录路径对「模型列表」的处理不对称。

| 路径 | 换推理 Key | 列模型 | 写服务商配置 |
|---|---|---|---|
| 如愿里登录如意账号（`ModelSetupViewModel.connectRuyi`） | ✅ | ✅ | ✅ |
| 如意里登录（`SignInController` → `RemoteSso.signIn`） | ✅ | ❌ | ❌ |

所以「在如意里登录、再回如愿看模型页」时，服务商配置还是登录前的旧快照：账号侧新增了模型（比如 `gemini-4-carbon` 上线），如愿里看不到，只能手动点刷新。`ensureListed()` 也救不了 —— 它只在 `provider.models == null` 时列一次，**已记住的列表永不自动更新**。

**改动 12** `ModelSetupViewModel.ensureModelsFresh()`（进模型页静默重列）

```kotlin
private const val MODEL_SYNC_TTL_MS = 10 * 60 * 1000L

fun ensureModelsFresh() {
    val p = store.ruyiAccount() ?: return
    if (!listable(p)) return
    val now = System.currentTimeMillis()
    syncedAt[p.id]?.let { if (now - it < MODEL_SYNC_TTL_MS) return }
    if (syncJob?.isActive == true) return
    // 先记时间再发请求：失败也按这个间隔退避，不然每次重组都重打一次不通的网络
    syncedAt[p.id] = now
    val before = p.models?.map { it.id to it.displayName }.orEmpty()
    syncJob = scope.launch {
        val listed = withContext(io) { runCatching { store.listModels(p) }.getOrNull() }
        val models = (listed as? ModelListResult.Ok)?.models?.takeIf { it.isNotEmpty() } ?: return@launch
        // 一模一样就别回写：rememberModels 会写一次配置文件、还会让整页重新组合，
        // 而「进来看看」是绝大多数情况，没变化时什么都不该发生。
        if (models.map { it.id to it.displayName } == before) return@launch
        withContext(io) { store.rememberModels(p.id, models) }
        val known = before.map { it.first }.toSet()
        val added = models.count { it.id !in known }
        // 源码里还有两条提前返回，这里略去：
        //   ① known 为空 —— 首次登录，还没有旧列表可比，不算新增
        //   ② added == 0 —— 只下架不新增，不打扰
        val name = store.provider(p.id)?.name ?: p.name
        _state.update { snapshot(it.copy(freshModels = FreshModels(name, added))) }
    }
}
```

四条设计约束，改动时别松：

1. **只碰如意账号那家**（`store.ruyiAccount()`），手填 API Key 的服务商一概不动 —— 那些列表就是用户自己填的，静默改写等于改用户的配置。
2. **失败静默**：`runCatching` 包住，拿不到就不动，绝不把网络错误铺到模型页。
3. **10 分钟 TTL + 单飞**：进页可能触发多次重组，`syncJob?.isActive` 挡住并发；时间戳**先写**再请求，失败也退避。
4. **无差量零副作用**：逐项比 `(id, displayName)`，完全相同就直接返回 —— 不写文件、不重组、不出提示。

**改动 13** 提示条（`FreshModelsNote`，`freshModels` 只在「有新增」时置位）

`ModelsCopy.freshModels(name, count)` → `R.string.models_fresh_note`（11 份 `strings_models.xml` 全量翻译）。渲染位置在 `ProvidersSection` 的 `probeNote` 之前，右侧「知道了」调 `dismissFreshModels()`。

> 为什么不只做静默重列：模型悄悄变了、用户又不知道，排障时会怀疑「明明账号里有这个模型」。一条会自己消失的提示成本极低。
> 为什么不做成弹窗/红点：模型列表不是关键路径，不值得打断。

### v1.6 追加（改动 14–15：修「回如愿登录」落回对话页）

**现象**（中继不可达专项里发现）：中继恢复后，在如意登录页点「回如愿登录」，实际落回**如愿对话页**，不是登录页，恢复流程走不下去。

**根因**：`RuyiLoginScreen` 顶部一句 `LaunchedEffect(signedIn) { if (signedIn != null) onSignedIn() }`。中继不可达时**如愿登录是成功的**（模型都拿到了），只有注册失败；所以从如意点进来时 `signedIn` 一开始就非空，页面组合的第一帧就 `onSignedIn()` → `nav.reset()`，人还没看清就被弹回对话页。

**为什么不能「直接重试注册」**：`RuyiSignIn.accessToken` 的既定约定是「只在内存里传一次，不落盘、不进日志」（`core/oauth/RuyiAuth.kt` 类注释）。中继恢复时那次登录的票早没了，`RemoteSso.signIn` 没有入参可用。**重新授权一次是唯一出路**。

**改动 14** `MainActivity`：给这条入口打来路标记 `ruyiLoginForRemote`，并记下进入时的 `RemoteSso.revisions` 值；登录页据此传 `autoAdvance = false`。

**改动 15** `RuyiLoginScreen` 新增 `autoAdvance: Boolean = true`

- `true`（默认，冷启动/设置里进来）：行为不变，登录成功就自动进主界面。
- `false`（回如愿登录）：**已经登录也不自动走**，停在页面上让用户点「账号登录」重新授权；已登录时补一行提示「已经登录。重新登录一次即可恢复远程控制。」（11 份 `strings_models.xml`）。

回如意的判据放在**宿主**：`LaunchedEffect(ssoRevision) { if (ruyiLoginForRemote && ssoRevision > ssoRevisionAtEntry) → 回如意 Tab }`。

> 这里踩过一次：一开始在登录页里用「进来时的 `signedIn` 快照」对比现在的 `signedIn`，想区分「本来就登录着」和「刚登录成功」。不成立 —— `signInState` 返回的 `signedIn` 是**服务商 id**，同一个账号重登时 `connectRuyi` 更新的是同一条记录、id 不变，前后完全相等，页面停在原地不动（实测：日志已经有 `远程控制已随如愿登录注册：device=01a1e319-…`，界面却还在登录页）。改成盯 `RemoteSso.revisions`（只在注册**成功**后 +1）就同时解决了两件事：知道「这次真的注册上了」，也知道该回哪儿。

### 不做的事

- 不改 `core/oauth/RuyiAuth.kt`（如愿登录链路已验证可用）。
- 不改中继任何代码或配置。
- 不改独立如意远程 App。
- 不动 `showSso = false` 的既有决策。

## 七、影响评估

1. **数据**：退出时删除本机 Keystore 里的 `credentialsKey` 与（`revokeDevice=true` 时）`identityKey`。不可逆，等价于用户主动退出登录，符合预期。
2. **网络**：退出时多一次 `revokeSelf` 请求（尽力而为，失败不阻断）。相比现在多一跳，量级可忽略。
3. **用户体验**：退出如愿登录后进远程控制会要求重新登录——这是**修正**而非回归（现状是残留账号）。
4. **兼容性**：`onRuyiSignedOut` 与 `onBackToHostSignIn` 都有默认值（`{}` / `null`），ViewModel 其他调用方、独立如意远程 App、Compose 预览均不受影响。
5. **安全**：消除换号串号风险；提示文案不含服务端错误原文。
6. **性能**：无热路径新增 IO。宿主每次登录/退出多一次本地 Keystore 读 + 一次 `refreshHosts`（本来登录后也要拉一次列表）。
7. **行为变化（需知会）**：`session.logout()` 现在**同步**把界面退回登录页，不再等 `account.logout()` 的网络往返；如意设置页点「退出登录」的观感从「等一会儿」变成「立刻」。
8. **状态一致性**：`restore()` 不再只升不降。以后任何「本机凭据被清」的路径（宿主退出、删服务商、Keystore 异常）都会把界面拉回登录页，不会留着一个失效账号的电脑列表。

## 八、回滚方案

- 触发条件：编译失败、退出后无法重新登录、或真机出现崩溃。
- 动作：`git revert` 本任务提交（预计 1 个提交），或 `git checkout de9102cd -- <涉及文件>` 还原 5 个文件；不涉及数据迁移，无状态残留。

## 九、执行与验收步骤

> **构建环境（踩过一次，务必照抄）**：
> `JAVA_HOME=/Users/binny/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home`
> `ANDROID_HOME=/Users/binny/Library/Android/sdk`
>
> PATH 上的 `java` 是 JDK 25（Zulu），Gradle 8.11.1 不认，报错只有一行 `What went wrong: 25.0.4`，看起来像项目坏了，实际是 JDK 版本。命令不要接 `| tail`（会掩盖退出码）。签名配置来自仓根 `app/release-signing.properties`（gitignored，本主工作树已有）。

1. 在当前主工作树 `ruyuan` 分支实施改动 1–8。（已完成）
2. `./gradlew :ruiyi-remote-ui:compileDebugKotlin :app:compileDebugKotlin` 编译通过。（已完成）
3. 相关单测：`./gradlew :ruiyi-remote-core:testDebugUnitTest`（若有新增用例），必要时补 `RemoteSso` 的失败分支单测。（当前 `ruiyi-remote-*` 无既有单测，未新增）
4. 出包安装到真机，按第二节 1–4 条逐项验证：
   - 登录后进远程控制 → 应直达「我的电脑」；
   - **不重启 App**、退出如愿如意账号 → 切到远程控制 → 应见登录页，提示「已随如愿退出而清除本机登录」；
   - 在该登录页点「回如愿登录」→ 应落在如愿的如意账号登录页（不是对话页）；登录成功后再切回远程控制 → 应自动进「我的电脑」，无需配对码；
   - 退出后再「设置 → 模型服务 → 删除如意服务商」→ 重登如愿 → 远程控制同样要求登录（设备令牌没留）；
   - 把中继临时指向不可达地址后登录 → 如愿模型照常、远程控制页提示中继不可达；
   - 其他服务商登录/退出回归。
5. 验收证据（截图 / logcat 关键字 `RemoteSso`、`RemoteSession`）回填本节。

### 已回填的验收证据（2026-11-16，模拟器 `emulator-5554`，包 `com.zl.ruyiai.yuan.dev`）

| 验收项（第二节） | 结果 | 证据 |
| --- | --- | --- |
| 登录后进远程控制直达「我的电脑」 | 通过 | 切 Tab 后为「主机列表 / 选择电脑 / 2 台在线」，无登录页、无配对码 |
| 不重启退出 → 登录页 +「已随如愿退出而清除本机登录」 | 通过 | `logcat RemoteSso: 远程控制已随如愿退出注销：revoke=true`；`ruiyi_remote_sso.xml` = `last_state=signed_out`；`dsh_remote_secrets.xml` 只剩 pin |
| 点「回如愿登录」落在如愿的如意账号登录页（非对话页） | 通过 | 屏上为如意账号登录页（顶部账号名 + 「使用如意账号登录，登录后自动获取…」+「账号登录」/「暂不登录」） |
| 重登后切回远程控制自动进「我的电脑」，无需配对码 | 通过 | `logcat RemoteSso: 远程控制已随如愿登录注册：device=01a1e2d5-be3b-7b05-a12a-e51aa1ac6d2a`，`force-stop` 前不重启即已登录 |
| 冷启动后仍保持登录 | 通过 | `force-stop` + 重启 → 远程控制直接「主机列表 / 2 台在线」 |
| 如愿侧模型服务商自愈 | 通过 | 「模型」页「如意（账号登录）· 已连接 · 21 个模型 · 主模型、轻量模型在用」 |
| 中继不可达时如意页提示 | 通过（恢复入口已修，见下条） | 模拟器临时阻断 TCP 8443 后授权，如愿服务商显示已连接，`last_state=unreachable`，如意页显示「连不上中继」。阻断已移除。 |
| 登录后模型变化能跟上（v1.5） | 通过 | 把 `providers.json` 的如意模型从 21 删到 19 → 冷启动进模型页 → 「如意 新增 2 个模型，列表已更新」+「知道了」，服务商行回到「已连接 · 21 个模型」；再 `force-stop` 冷启动进页 → 无提示条，`providers.json` 的 `md5`（`42529cab…`）与 `mtime`（14:31:03）均未变，确认「无差量零副作用」 |
| 「回如愿登录」在已登录态不再被弹走（v1.6） | 通过 | 造「如愿已登录 + 如意已退出」现场（凭据只留 pin、`last_state=signed_out`）→ 点「回如愿登录」→ 停在如愿登录页，且显示「已经登录。重新登录一次即可恢复远程控制。」（截图 `/tmp/t03_restore_entry.png`） |
| 重新授权一次即恢复，且回的是如意（v1.6） | 通过 | 同上点「账号登录」→ 星桥授权 → `logcat RemoteSso: 远程控制已随如愿登录注册：device=01a1e31d-…` → **自动切回如意 Tab**，屏上「主机列表 / 2 台在线」 |
| 其他服务商登录/退出回归 | 未做 | 见待办 ① |

> 模拟器验收的两个操作前提（不写下来会反复踩）：① 三个如愿包（`yuan` / `.perf` / `.dev`）都会处理 `ruyiai://auth2/callback`，必须 `pm disable-user` 另外两个，否则授权回来弹 Resolver；② 授权码 60 秒有效，授权页一旦出现「登录请求无效」只能回 App 重新点「账号登录」，重开旧链接无效。

## 十、进度快照

| 时间 | 事件 |
| --- | --- |
| 2026-11-16 10:52 | 方案 v1.0 产出，待用户确认；未改任何代码。已排除「relayBase 漂移」误判。 |
| 2026-11-16 10:56 | 修正 v1.0 一处断言：`RemoteAccount.logout` 并非无调用点，如意设置页（`SettingsScreen.kt:77`）会调；缺的是如愿退出路径的调用。同步补「反向不联动」为技术不可实现项及兜底。 |
| 2026-11-16 11:05 | 用户确认按 v1.1 全做（P1+P2）。 |
| 2026-11-16 11:10 | 改动 1–5 落地，共 4 个文件：`RemoteSso.kt`（新增 `signOut` / `lastState`）、`ModelSetupViewModel.kt`（`onRuyiSignedOut` 参数 + 如意分支）、`ModelSetupScreen.kt`（注入）、`ConnectScreen.kt`（首屏提示）。 |
| 2026-11-16 11:12 | 编译通过：`BUILD SUCCESSFUL in 16s`，`:ruiyi-remote-ui:compileDebugKotlin` + `:app:compileDebugKotlin`。首次失败因 PATH 上 JDK 25，换 JBR 21 后通过（见第九节环境说明）。 |
| 2026-11-16 11:18 | 用户选方案①（两处缺口一起补）。核查发现第三处更关键的缺口：`RemoteHome` 常驻组合 + `restore()` 只升不降 → 退出后不重启就一直显示上个账号的电脑列表，「回如愿登录自动恢复」也不成立。 |
| 2026-11-16 11:20 | 改动 6–8 落地，累计 8 个代码文件 + 2 个文档（清单见下）。编译通过：`BUILD SUCCESSFUL in 5s`；独立 App `:ruiyi-remote:compileDebugKotlin` 回归通过（`BUILD SUCCESSFUL`）。 |
| 2026-11-16 12:15 | 模拟器（`.dev` 包）验收揪出真根因：登录成功 `RemoteSso: 远程控制已随如愿登录注册` 后，如意仍停登录页。定位为 `RemoteAccount.restore()` 的 `restored` 内存缓存短路重读（`RemoteSso` 与常驻 `RemoteHome` 是**两个** `RemoteAccount` 实例、写同一 Keystore）。改动 9–11：`reload()` + `restore()` 读前失效缓存 + `ssoState` 改响应式 `state` 流。编译通过，`.dev` 包重装后验证：① 冷启动如意直达「主机列表 / 2 台在线」；② 删除如意服务商（触发 `signOut`）后**不重启**切如意 → 登录页 + 「已随如愿退出而清除本机登录」+「回如愿登录」，logcat `远程控制已随如愿退出注销：revoke=true`，凭据文件清空、`last_state=signed_out`。 |
| 2026-11-16 13:31 | **登录方向闭环走通**（同一台模拟器、`.dev` 包，全链路无重启）：删除如意服务商（`revoke=true` / `last_state=signed_out`）→ 抽屉「远程控制」→ 登录页见提示 +「回如愿登录」→ 点按钮落在**如愿的如意账号登录页**（非对话页）→「账号登录」拉起星桥授权页 →「允许并打开客户端」→ 深链 `ruyiai://auth2/callback` 直达 `.dev`（release/perf 已 `disable-user`）→ logcat `RemoteSso: 远程控制已随如愿登录注册：device=01a1e2d5-…`、`ruiyi_remote_sso.xml` 清空（`clearState` 生效）→ **不重启**切如意 → 直接「主机列表 / 2 台在线」，「我的电脑」面板同样「2 台在线」，凭据可用。再 `force-stop` 冷启动 → 仍已登录（`reload()` 无回退）。如愿「模型」页服务商自动恢复为「如意（账号登录）· 已连接 · 21 个模型 · 主模型、轻量模型在用」。 |

| 2026-11-16 14:33 | **改动 12–13 落地并验收**（登录后模型变化能跟上）：`ensureModelsFresh()` 进模型页静默重列（10 分钟 TTL + 单飞 + 失败静默 + 逐项比对无变化即返回）+ `FreshModelsNote` 提示条（11 份 `strings_models.xml`）。`ModelSetupViewModelTest` 52 个用例全绿（新增 2 个），`:app:assembleDebug -PdevInstall` 出包 150M 装 `.dev`。模拟器两场景实测：有差量出提示并回写 21；无差量不出提示、配置文件 `md5`/`mtime` 不变。 |
| 2026-11-16 14:50 | **改动 14–15 落地并验收**（修「回如愿登录」落回对话页）：`MainActivity` 打来路标记 + 盯 `RemoteSso.revisions` 判「注册成功」，`RuyiLoginScreen` 新增 `autoAdvance`（已登录也不自动走）+ 已登录提示（11 份 strings）。首版用「signedIn 快照对比」判成功，实测失败（服务商 id 重登不变），改用注册计数后通过。模拟器两段实测：停在登录页 + 提示；重新授权后 `device=01a1e31d-…` 自动回如意、主机列表 2 台在线。发现一处形态问题：`last_state` 被清空时登录页不给「回如愿登录」入口（人工清凭据才会遇到）。 |
### 改动文件清单（提交前分支 `ruyuan`，基线 `9ee97378`）

> `app/build.gradle.kts` 的版本号和 Beta 标记属于 T04 发版，已在单独的提交 `7de308a2` 中，不属于本任务。


| 文件 | 改动 |
| --- | --- |
| `ruiyi-remote-core/.../account/RemoteAccount.kt` | 新增 `reload()`：失效 `restored` 内存缓存并重读 Keystore（v1.4 根因修复） |
| `ruiyi-remote-ui/.../ui/RemoteSso.kt` | 新增 `signOut` / `lastState` / `revisions` / `state` / `classify`；`signIn` 记状态 + 成功后 `revisions+1`；`state` 流镜像 `lastState` |
| `ruiyi-remote-ui/.../ui/RemoteSession.kt` | `restore()` 双向 + 读前 `account.reload()`；抽出 `clearSession()`；`logout()` 复用它且同步退栈 |
| `ruiyi-remote-ui/.../ui/screen/ConnectScreen.kt` | `ssoState` 改订阅 `RemoteSso.state`（响应式）+ `onBackToHostSignIn` 按钮 |
| `ruiyi-remote-ui/.../ui/AppNav.kt` | `DshApp` 增加 `onBackToHostSignIn` 并透传 |
| `ruiyi-remote-ui/.../ui/RemoteHome.kt` | 增加 `onBackToHostSignIn`；订阅 `revisions` 触发 `restore()` |
| `app/.../ui/ModelSetupViewModel.kt` | `onRuyiSignedOut` 参数；`signOutAccount` 如意分支；`removeProvider` 判据改 `signIn` |
| `app/.../ui/ModelSetupScreen.kt` | 注入 `RemoteSso.signOut` |
| `app/.../ui/RemoteScreen.kt` | 透传 `onBackToHostSignIn` |
| `app/.../ui/MainActivity.kt` | 注入「回如愿登录」→ 切 Tab + `nav.show(Route.RuyiLogin)` |
| `TASKS.md` · `docs/tasks/T03-...md` | 状态与方案/进度 |
| `app/.../ui/ModelSetupViewModel.kt` | v1.5：`ensureModelsFresh()`（TTL + 单飞 + 失败静默 + 无差量即返回）、`FreshModels` / `freshModels` / `dismissFreshModels`、`connectRuyi` 记录同步时刻 |
| `app/.../ui/ModelSetupScreen.kt` | v1.5：进页 `LaunchedEffect` 触发同步；`FreshModelsNote` 提示条 + `dismiss` |
| `app/.../ui/ModelsScreen.kt` | v1.5：`ModelsCopy.freshModels(name, count)` |
| `app/src/main/res/values*/strings_models.xml`（11 份） | v1.5：`models_fresh_note` 全语种 |
| `app/src/test/.../ModelSetupViewModelTest.kt` | v1.5：新增 2 个用例（有新增出声 / 无新增不出声） |
| `app/.../ui/RuyiLoginScreen.kt` | v1.6：新增 `autoAdvance`（回如愿登录这条路已登录也不自动离开）+ 已登录提示 |
| `app/.../ui/MainActivity.kt` | v1.6：来路标记 `ruyiLoginForRemote` + `ssoRevisionAtEntry`，`LaunchedEffect(ssoRevision)` 判「注册成功」后回如意 |

### 实施中的两处修正（相对 v1.1）

1. **提示文案方向错误已改**。v1.1 写的是「可在这里直接登录」，但本屏 `showSso=false`、没有如意账号入口，那句话会把用户引向配对码/账号密码这条岔路，等于制造第二个登录。现改为「回到如愿用如意账号登录一次，这里会自动恢复」。
2. **状态标记由「失败」扩为「状态」**。`KEY_FAILURE` → `KEY_STATE`，取值增加 `signed_out`：退出后不能清空标记，否则登录页没有任何线索解释「为什么又要登录」。

### 原「未覆盖」两处缺口（v1.3 已补齐）

| 缺口 | 位置 | 处理 |
| --- | --- | --- |
| 删除如意服务商不注销设备 | `ModelSetupViewModel.kt:1176` `removeProvider` | 判据改 `provider.signIn == RUYI_SIGN_IN`，调 `onRuyiSignedOut()`（改动 6） |
| 登录页无「回如愿登录」入口 | `ConnectScreen` 只有一行文字 | 提示下加按钮，逐层透传后在 `MainActivity` 注入「切 Tab + 进如意账号登录页」（改动 7） |

### 已知限制（有意保留，非缺陷）

| 项 | 说明 |
| --- | --- |
| 如意侧退出不反向通知如愿 | 库与宿主单向依赖（`RemoteHome.kt:45`）。本任务只做「如愿 → 如意」单向。 |
| 设备注册失败仍不阻断如愿登录 | 有意为之：模型已可用，远程可走配对码；失败由首屏提示 + `RemoteSso` 日志暴露。 |

### 中继不可达专项补充（2026-11-16）

模拟器临时阻断出站 TCP 8443，删除并重新授权如意服务商后，服务商页显示已连接，注册失败日志出现，状态落盘为 `unreachable`；如意页实际显示「连不上中继」提示。已移除临时阻断规则。**限制**：未实测模型推理；恢复网络后点「回如愿登录」实际落回对话页，未能完成重新注册。代码中 `RuyiLoginScreen` 的 `LaunchedEffect(signedIn)` 在已有账号时会立即调用 `onSignedIn()`，与现象相符，仍需进一步验证及修复。测试期间曾出现一次 ANR，堆栈在 Compose 布局，因果未确定。

### 接手前置（目标 / 现状 / 下一步）

- **目标**：如愿登录即如意就位、如愿退出即如意退出、失败可感知。
- **现状**：改动 1–15 全部落地并编译通过（`ruiyi-remote-core` + `ruiyi-remote-ui` + `app` + 独立 `ruiyi-remote` 回归）。模拟器（`.dev` 包）**双向闭环均已实测通过**：退出方向（删除如意服务商 → 不重启切如意 → 登录页 + 提示 +「回如愿登录」，`revoke=true`）与登录方向（「回如愿登录」→ 如愿如意账号登录页 → 星桥授权 → 不重启切如意 → 主机列表 / 2 台在线，`device=01a1e2d5-…`），冷启动后仍已登录。v1.5 的「登录后模型变化能跟上」也已实测（有差量出提示并回写、无差量零副作用）。**未提交**。
- **待办**：① 其余服务商登录/退出回归（ChatGPT / xAI / OpenRouter 不经过 `RemoteSso`，属旁路回归）；② 补测模型实际推理（登录、模型列表、远程连接均已实测，唯独没跑过一次真实推理）；③ 验收通过后由用户授权提交；④ 提交时 `git add` 新文件 `docs/tasks/T03-*.md`（`docs/*` 是目录级 ignore，可能要 `git add -f`）。
