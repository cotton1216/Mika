# iOS 可行性落地：网关控制台 PWA 可下发任务

- 任务ID：mika-ios-web-console
- 责任人：binny（决策与验收）；AI（文档、实现与记录）
- 更新时间：2026-11-11
- 状态：已达成共识（实现中）

## Why

Mika 目前是 Android 原生应用（AGP `com.android.application`，Kotlin + Compose），`:core` 是 `kotlin.jvm` 而非 KMP，依赖 OkHttp、protobuf-javalite、commons-compress/xz 与 `java.*` API，**不能编译到 iOS**。这不是构建配置问题：核心能力（AccessibilityService 跨 App 读屏点击、MediaProjection 虚拟屏、`InCallService` 通话助手、adb/shell 助手、proot 本地 Linux）在 iOS 上既无公开 API，也违反 App Store 2.5.1/2.5.2。

但 Mika 已经有一条不依赖 Android 特有能力的通路：`tools/relay` Go 网关 + 手机端出站 WSS 通道。现有 `/app` 只读控制台已经能在任意浏览器（含 iOS Safari）里看到设备状态。把它从「只看」升级为「可下发任务」，就能在 iOS 上用 Safari / 添加到主屏幕完成主要工作流，且不触碰 Android 主线、不需要审核。

因此本变更只做「最小可用的 iOS 入口」：**网关 PWA 控制台**。真正的执行仍在手机上。

## What Changes

1. **手机端 relay 作用域投影**（`core/src/main/kotlin/com/wangzan101/mika/core/remote/`）
   - 新增 `GET /api/v1/running`、`GET /api/v1/conversations`、`GET /api/v1/conversations/{id}`，仅在 relay 通道可用。
   - `/api/v1` 的 `capabilities` 追加 `running`、`conversations`。
   - 只暴露 relay 归属（owner `relay`）任务及其对话，**不暴露用户本机个人历史**。
2. **网关写白名单**（`tools/relay/console.go`）
   - 放行 `POST /api/v1/tasks`、`POST /api/v1/tasks/{id}/answer|steer|cancel`。
   - 写请求强制 `Origin == base` + `X-Mika-Console: 1`、会话有效、写限流 30/min、请求体 8 KiB，仍禁止 `DELETE`。
3. **控制台 UI**（`tools/relay/web/console.{html,js,css}`）
   - 新增输入框发任务；运行中任务卡片增加插话、回答追问、取消（二次确认）。
   - 3 秒轮询刷新，不做长轮询（隧道并发上限 8）。
4. **PWA 壳**：`web/manifest.webmanifest` + 图标 + `apple-mobile-web-app-capable` + `display: standalone` + safe-area。

不做（本期）：

- 不暴露手机本机个人历史、不做历史搜索。
- 不做附件上传/下载、不做 run 导出。
- 不做 iOS Web Push、不做 Service Worker 离线缓存。
- 不把支付确认、支付类不可逆操作放到控制台（与现有 README 立场一致）。
- 不做原生 iOS 客户端、不做 `:core` KMP 化、不改动 Android 主线功能。

## 可实现性分类

| 项 | 分类 | 依据 | 原因 | 兜底 | 回滚 |
| --- | --- | --- | --- | --- | --- |
| 网关 PWA 控制台看运行中任务、发任务、插话/回答/取消 | 可实现项 | `tools/relay/console.go` 已有登录、会话、GET 转发；`core/remote/RemoteTaskApi.kt` 已有 `POST /tasks`、`/answer`、`/steer`、`/cancel` | 现有能力已具备，只缺 relay 侧读接口与网关写白名单 | — | 见「回滚方案」 |
| 手机端 relay 作用域投影 | 可实现项 | `RemoteTasks.scope(owner)` 已隔离任务；`RemoteEndpoints.handle` 已按 owner 构造 service | 归属隔离机制已存在，投影是新增只读接口 | — | 关闭 capability 即回到现状 |
| iOS 添加到主屏幕、全屏运行 | 可实现项 | Safari 支持 `manifest` 与 `apple-mobile-web-app-capable` | 纯前端声明 | 退化为普通 Safari 标签页使用 | 移除 manifest 引用 |
| `:core` KMP 化后在 iOS 复用 core | 当前暂不实现项 | `core/build.gradle.kts` 为 `kotlin.jvm`，依赖 OkHttp/commons-compress/protobuf-javalite/`java.*` | 需替换网络层、压缩、加密与序列化，并改写 `runBlocking` 调用点，工作量与回归面远超本期 | 需要时再立独立变更；在此之前 iOS 走网关联通 | 停用 KMP target，不影响 Android |
| iOS Web Push 实时提醒 | 当前暂不实现项 | iOS 16.4+ 要求已添加到主屏幕的 PWA，且权限/投递不受控 | 依赖用户安装 PWA 且不可保证送达，本期用 3 秒轮询 | 轮询 + 页面标题提示；后续再评估 | 移除推送订阅代码 |
| Service Worker 离线可用 | 当前暂不实现项 | 控制台数据必须实时，缓存会产生「假在线」 | 离线展示会误导用户以为能下发任务 | 断网时显示手机不在线 | 不注册 SW |
| 附件上传/下载、run 导出 | 当前暂不实现项 | 现有 `/tasks/{id}/export`、上传分片已存在，但网关需要额外白名单与流式处理 | 超出最小闭环 | 需要时在电脑端 CLI/MCP 完成 | 不加白名单 |
| 用同一份 Kotlin/Compose 代码产出 iOS 原生 App | 技术不可实现项 | 编译产物为 APK/AAB；core 非 KMP | Android 专属系统能力 + JVM 专有依赖 + 审核限制；解除条件：iOS 提供等价的跨 App 自动化与虚拟屏公开 API（当前不存在） | 走网关 PWA / 未来原生 Lite | 不适用 |
| 跨 App 读屏点击、后台隐形屏、本地 Linux 沙箱在 iOS 复刻 | 技术不可实现项 | Manifest 中 AccessibilityService、`TaskService`（虚拟屏）、proot/rootfs | iOS 无第三方跨 App UI 自动化、无虚拟屏、禁 fork/exec 与 JIT；私有 API 会被审核拒绝。解除条件：仅越狱环境（不纳入产品） | 这些能力保留在手机端，控制台只下发任务 | 不适用 |
| iOS Safari 添加到主屏幕后 Cookie 保留时长 | 待确认项 | 未实测 | 会话 Cookie 为 `HttpOnly + SameSite=Strict + Path=/web`、8h 空闲过期；ITP 清理策略可能缩短实际驻留 | 过期即重新输入访问令牌；必要时下调空闲时长 | 回退到每次使用重新登录 |
| 隧道 8 并发下写请求排队表现 | 待确认项 | `RelayClient` 超限返回 429 `device_busy` | 写操作与轮询共享并发额度 | 写失败提示「手机忙，稍后重试」；轮询失败不打扰 | 降低轮询频率 |

## 方案要点

### 接口契约（手机端 relay 作用域）

沿用 `console.js` 现有读取形状，避免改动已上线的只读 UI 渲染逻辑。

1. `GET /api/v1/running` → `200`
   ```json
   { "running": [ { "task_id": "…", "status": "running", "conversation": "…",
                    "goal": "…", "step": 3, "progress": "…", "question": "…",
                    "options": ["…"], "needs_you": true, "answerable": true,
                    "started_at_ms": 1762800000000 } ] }
   ```
   - 只含未终结状态（`starting/running/queued/paused/waiting_for_answer/waiting_on_phone`）。
   - `needs_you` 由投影补出：`waiting_for_answer`、`waiting_on_phone` 为 `true`（`RemoteStatus.describe` 本身不含该字段，而 `console.js` 依赖它）。
2. `GET /api/v1/conversations?offset&limit`（默认 `limit=30`，上限 100）→ `200`
   ```json
   { "conversations": [ { "conversation": "…", "title": "…", "updated_at_ms": 1762800000000, "turn_count": 4 } ],
     "next_offset": 30 }
   ```
3. `GET /api/v1/conversations/{id}?from&limit`（默认 `limit=10`，上限 50）→ `200`
   ```json
   { "conversation": "…", "title": "…", "total": 12, "from": 0,
     "turns": [ { "user_text": "…", "outcome": "…",
                  "status": "done", "note": "…", "truncated": false } ] }
   ```
   - `from` 是窗口起始下标（从最早一轮记为 0）；缺省时取最新一页，响应回显实际 `from`。「加载更早」按 `from - limit` 请求，前后两页不重叠。
   - 非 relay 归属的对话一律 `404 not_found`，不区分「不存在」与「无权限」。

### 归属与隔离（必须守住）

- `RemoteEndpoints.handle` 中 relay 通道当前为 `historyAccess = false`。本变更**不放宽该开关**，而是新增一个只读投影：数据源为 `RemoteTasks.scope("relay")` 的任务集，历史部分只取这些任务对应的对话与轮次。
- 判定规则：对话或轮次必须能关联到 relay 归属任务（`turn.remoteTaskId` 命中 relay 任务集合，或该对话由 relay 任务创建）。`PhoneHistory` 现在读取整份 `ConversationStore`，**不得直接复用其无条件返回**。
- `MikaRuntime.kt` 中 relay 的 `handle(req)` 调用点保持不变；投影作为独立参数注入，避免任何路径出现「本机全量历史对 relay 可见」。

### 网关写白名单与安全边界

- 路径白名单新增：`POST /api/v1/tasks`、`POST /api/v1/tasks/{id}/answer`、`POST /api/v1/tasks/{id}/steer`、`POST /api/v1/tasks/{id}/cancel`。GET 白名单保持现状。
- 写请求校验顺序：Cookie 会话有效 → `Origin` 为空或等于 `g.base`（不等则 `403 invalid_origin`）→ `X-Mika-Console: 1` 必需（缺失 `403 console_header_required`）→ 路径命中白名单（未命中 `404`）→ 写限流 30 次/分钟（超限 `429` + `Retry-After`）→ 请求体上限 8 KiB（超限 `413`）。
- 转发前剥离 `Cookie`、`Origin`，注入 `Authorization: Bearer <手机 token>`（沿用现有 `consoleProxy` 逻辑）。浏览器永远拿不到手机 token。
- `DELETE` 仍然禁止；取消任务走 `POST /tasks/{id}/cancel`，UI 二次确认。
- 支付与不可逆确认不进控制台。

### 控制台与 PWA

- `console.html`：运行区新增任务输入框与「发送」；运行卡片在 `answerable` 时显示回答输入、在未终结时显示「插话」与「停止」。
- `console.js`：`POST` 复用 `api()`，统一带 `X-Mika-Console: 1`；失败按 `error.code` 提示；轮询保持 3 秒，`document.hidden` 暂停。
- `manifest.webmanifest`：`name/short_name/start_url: "/app"`、`display: "standalone"`、`theme_color`、`background_color`、192/512 图标；`console.html` 增加 `apple-mobile-web-app-capable`、`apple-touch-icon`、`viewport-fit=cover` 与 safe-area 内边距。

## 验收标准

- `cd tools/relay && go test ./...` 通过；`cd Mika && ./gradlew :core:test` 通过。
- iOS Safari 真机（添加到主屏幕）走通：输入访问令牌 → 看到运行中任务 → 发一条任务 → 任务追问时回答 → 插话 → 取消。
- 桌面浏览器同样可用；离线时显示「手机不在线」，不展示过期数据作为实时状态。
- 手机端本机个人对话不出现在控制台列表与详情（构造两台设备或本机新建个人对话后验证）。
- 网关写请求缺失 `X-Mika-Console` 或 `Origin` 不匹配时返回 403；超过 30 次/分钟返回 429；8 KiB 以上请求体被拒。
- 未配对/手机离线时控制台仍可打开，明确提示设备离线。

## 阻塞/依赖

- 依赖 `mika-relay-prod-deploy` 已上线的网关与 `mika.kfxh.work` 域名（已生效）；本变更需要重新构建镜像并 reload。
- 手机端与网关需同期发布：新控制台在旧手机上会因缺少 `running`/`conversations` capability 走 `unsupported()` 提示（已有降级，不会白屏）。
- 需一台 iOS 真机做验收；无 iOS 设备时验收降级为 Safari 桌面模式 + 移动视口模拟，并标注未经真机确认。

## 影响评估

| 评估项 | 结果 |
| --- | --- |
| 改动对象与范围 | 手机端只新增只读接口与 capability；网关只新增 POST 白名单与校验；控制台前端新增交互。不改 Android 现有功能与任务执行链路。 |
| 上下游模块影响 | 不改变 `/mcp` 与 CLI 行为；不改变 `RemoteTasks.scope` 语义；不改变配对流程。旧版控制台页面仍可只用 GET。 |
| 数据结构影响 | 无表结构变更；沿用现有任务与对话存储。 |
| 性能/并发影响 | 新增轮询命中两个读接口，均为内存投影 + 分页；写请求共享隧道 8 并发额度，需真机验证排队表现（待确认项）。 |
| 安全影响 | 新增写入口，风险集中在 CSRF 与会话泄漏：靠 `Origin` + 自定义头 + `SameSite=Strict` Cookie + 写限流 + 体量上限收敛；手机 token 仍只存在网关内存。 |
| 兼容性 | 旧手机 + 新网关 → 控制台显示不支持；新手机 + 旧网关 → 手机新接口不可达但不影响旧功能。 |
| 回归范围 | 任务下发/回答/插话/取消（CLI 与 MCP 双通道）、网关登录/登出、只读控制台渲染。 |
| 灰度要求 | 不需要灰度；先本地 `go test` 与 `:core:test`，再部署网关，最后装新 APK。 |

## 回滚方案

触发条件：控制台写操作出现越权/跨会话可见、任务下发后手机端异常、网关 5xx 比例上升、iOS 真机无法登录。

动作（按粒度递增）：

1. 关闭写入口：将 `consolePath` 写白名单收回为 GET-only，重新构建并重启容器 → 控制台退化为只读，手机端新接口仍保留但不可达。
2. 关闭投影：`/api/v1` capability 去掉 `running`、`conversations`，控制台自动进入 `unsupported()` 提示。
3. 整体回退：`docker compose -p mika-relay -f compose.nginx.yaml` 回到上一镜像标签；Android 侧回退到上一 APK（`applicationIdSuffix` 版本可共存，不覆盖已装正式包）。

不删除 `mika-relay_relay_data` 卷，配对与 owner key 保留，便于重试。

## 用户决策闭环

| 事项 | 结论 | 责任人 | 时间 |
| --- | --- | --- | --- |
| iOS 方向 | 选 C′：网关 PWA 先行，不做原生 iOS 客户端与 KMP 化 | binny | 2026-11-11 |
| 实施方式 | 先落本变更文档，评审通过后实现 Phase 1 | binny | 2026-11-11 |
| 本机历史是否暴露 | 不暴露，只投影 relay 归属对话 | binny | 2026-11-11 |
| 支付类操作 | 永不上控制台 | binny | 2026-11-11 |
| iOS 真机验收 | 待确认可用设备 | binny | 待定 |
