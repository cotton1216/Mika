## 1. 前置核查

- [x] 1.1 确认 Mika 是 Android 原生应用（`com.android.application`、compileSdk 36），`:core` 为 `kotlin.jvm`，无 iOS target（`settings.gradle.kts` 仅 `:core`、`:app`）
- [x] 1.2 确认 `:core` 依赖 OkHttp、protobuf-javalite、commons-compress/xz 与 `java.*`，不能直接编译到 iOS
- [x] 1.3 确认 iOS 侧缺失的核心能力（跨 App 自动化、虚拟屏、fork/exec、adb/shell、`InCallService`）无公开 API，且与 App Store 2.5.1/2.5.2 冲突
- [x] 1.4 确认可复用的远程通路：`tools/relay` 网关 + 手机端出站 WSS；`/app` 只读控制台已上线
- [x] 1.5 确认控制台读取的 `/api/v1/running`、`/api/v1/conversations` 在手机端不存在，且 relay 通道固定 `historyAccess = false`（`McpRuntime.kt`），故必须新增 relay 作用域投影
- [x] 1.6 确认 `consolePath` 当前为 GET-only 白名单，`console.js` 已按 `running`/`conversations` 形状渲染
- [x] 1.7 用户评审本变更文档，确认 Phase 1 范围与接口契约（2026-11-11 用户确认「直接进入实现，视为评审通过」）

## 2. 手机端 relay 作用域投影（`:core`）

- [x] 2.1 新增只读投影类（如 `RelayHistoryView`），数据源限定 `RemoteTasks.scope("relay")` 的任务集，历史部分只取能关联到 relay 任务的对话与轮次；不复用 `PhoneHistory` 的无条件全量返回
- [x] 2.2 实现 `GET /api/v1/running`：未终结任务列表，补 `needs_you` 字段
- [x] 2.3 实现 `GET /api/v1/conversations?offset&limit`：`limit` 默认 30、上限 100，返回 `conversations` + `next_offset`
- [x] 2.4 实现 `GET /api/v1/conversations/{id}?from&limit`：`limit` 默认 10、上限 50，`from` 为窗口起始下标（最早一轮记 0），缺省取最新一页；非 relay 归属返回 404
- [x] 2.5 `/api/v1` capability 追加 `running`、`conversations`；`RemoteEndpoints.handle` 注入投影，但不放宽 `historyAccess`
- [x] 2.6 单测：跨 owner 404、分页 `next_offset`/`from` 边界、空集、`limit` 越界收敛、`needs_you` 映射

## 3. 网关写白名单（`tools/relay`）

- [x] 3.1 `consolePath` 增加写路径白名单：`POST /api/v1/tasks`、`/tasks/{id}/answer|steer|cancel`；GET 白名单不变
- [x] 3.2 `consoleProxy` 支持 POST：方法校验、`Origin == g.base`、`X-Mika-Console: 1`、会话校验、写限流 30/min、请求体 8 KiB、`DELETE` 仍拒绝
- [x] 3.3 转发前剥离 `Cookie`/`Origin` 并注入 Bearer；错误统一走 `failure()` 信封
- [x] 3.4 `go test ./...`：新增用例覆盖 403（缺头/错 Origin）、404（非白名单路径/子路径过深）、405（DELETE）、429（限流）、413（超限体积）

## 4. 控制台 UI 与 PWA

- [x] 4.1 `console.html` 增加任务输入框与发送按钮、运行卡片上的回答/插话/停止控件、`X-Mika-Console` 相关无障碍说明
- [x] 4.2 `console.js` 增加 POST 调用与错误提示；停止需二次确认；轮询保持 3 秒、`document.hidden` 暂停
- [x] 4.3 `console.css` 适配 iOS safe-area，移动端可单列滚动
- [x] 4.4 新增 `web/manifest.webmanifest` 与 192/512 图标，`console.html` 加 `apple-mobile-web-app-capable`、`apple-touch-icon`、`viewport-fit=cover`
- [x] 4.5 `web.go` 支持 `manifest.webmanifest` 与图标资源的 Content-Type

## 5. 验证与发布

- [x] 5.1 本地 `go test ./...`、`./gradlew :core:test` 通过
- [x] 5.2 网关部署（重新构建镜像、reload），验证 `/app` 可打开、只读路径仍 200
- [ ] 5.3 装新 APK，iOS Safari 真机走通「登录 → 看运行 → 发任务 → 回答 → 插话 → 取消」
- [ ] 5.4 隔离验证：手机本机新建个人对话，确认不出现在控制台列表与详情
- [x] 5.5 安全验证：缺 `X-Mika-Console` 403、错 `Origin` 403、超 30 次/分钟 429、8 KiB 以上 413
- [ ] 5.6 旧手机 + 新网关走 `unsupported()` 降级，不白屏

## 部署记录（5.2，2026-11-11）

- 源码同步：rsync 至 `/opt/mika-relay`，排除 `Dockerfile`、`.env`、`owner-key`、`.git`、`data`（服务器副本 Dockerfile 的 `ARG GOPROXY` 不能覆盖）
- 镜像重建：`docker compose -p mika-relay -f compose.nginx.yaml build --build-arg GOPROXY=https://goproxy.cn,direct`（构建阶段含 `go test ./...`，通过）；`up -d` 后 `mika-relay-relay-1` Up、`Restarts=0`、仅监听 `127.0.0.1:18787`
- 偏离：站点模板 `location ~ .*\.(gif|jpg|jpeg|png|bmp|swf)$` 优先命中图标，导致 `/icon-*.png`、`/apple-touch-icon.png` 落 webroot 后经 `error_page` 回落网关返回 `not_found`。扩展配置 `extension/mika.kfxh.work/relay.conf` 第 5 行正则扩为 `^/((app|style|console)\.(css|js)|(icon-(192|512)|apple-touch-icon)\.png)$`；备份 `relay.conf.bak-20261111-pwa`，`nginx -t` 通过后 reload
- 验证：`/healthz`、`/app`、`/console.css`、`/console.js`、`/manifest.webmanifest`、`/icon-192.png`、`/icon-512.png`、`/apple-touch-icon.png` 全部 200，Content-Type 依次为 `application/json`、`text/html`、`text/css`、`text/javascript`、`application/manifest+json`、三处 `image/png`
- 隧道状态：部署后 `/api/v1` 由 503 `device_offline` 变为 401 `access_denied`（`{"error":{"code":...,"message":"Missing or wrong access token"}}`，手机端信封），说明手机已重连、请求已抵达手机鉴权层，无 Bearer 被拒属预期；`/web/session` 返回 `{"connected":false}` 仅表示无控制台 Cookie，不代表隧道状态

## 安全实测记录（5.5，2026-11-11）

- 生产（无会话，验证门禁顺序与限流）：
  - `POST /web/login` 连打 11 次 → 前 10 次 `400 invalid_token`，第 11 次 `429 rate_limited`（10/min 生效）
  - `GET /web/api/v1/running` → `401 not_connected`；`POST /web/api/v1/tasks`（带正确 Origin 与头）→ `401 not_connected`；非白名单 `POST /web/api/v1/tasks/1/export` → `401 not_connected`
  - `DELETE /web/api/v1/tasks/1` → `405 method_not_allowed`，`Allow: GET, POST`
- 本地 Go 用例（`go test -run 'TestConsoleWritesAreWhitelistedAndBudgeted|TestConsoleLoginRejectsForeignOrigin|TestConsoleRequiresSessionAndOnlyAllowsReadPaths' -v`）PASS，逐项对应：缺 Origin 403、外站 Origin 403、缺 `X-Mika-Console` 403、未列写路径 404、只读路径写 405、`8193` 字节 413、第 31 次写 429
- 结论与限制：生产端 403/413 必须在已登录会话下才可达（`consoleProxy` 先校验 Cookie，未登录一律 401 `not_connected`），故这两类以本地用例为准；生产已确认鉴权门禁与限流顺序无绕过

## 待确认

- [ ] iOS 添加到主屏幕后，`Path=/web` 的 8h 会话 Cookie 实际保留时长（ITP 影响）
- [ ] 隧道 8 并发额度下写请求与 3 秒轮询的排队表现；必要时下调轮询频率或写操作串行
- [ ] iOS 真机验收设备可用性；无真机时降级为桌面 Safari + 移动视口并标注
