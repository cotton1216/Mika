# T15 — 手机端 Markdown 图片按工作区路径拉图

## 目标
修复 PC 端生成的手抄报图片在手机端只显示 `![书香伴我成长手抄报](<reading-handwritten-newspaper.png>)` 文本、不显示图片的问题。采用纯手机端改造：解析 markdown 相对图片路径，从会话工作区按路径读图并渲染，不改 host / relay / 协议。

## 背景与根因
- PC agent 把生成图写成工作区文件，消息正文只保留 markdown 相对路径，**没有协议图片块**（无 base64 / attachmentId），手机端 `messageImages()` 只认 `type == "image"` 的协议块，无从拉取。
- 手机 Markdown 渲染器（`MarkdownContent`）此前不解释 markdown 图片语法，`![alt](path)` 走普通 `[链接]` 分支被跳过，只剩机器格式文本。
- `session/attachment` 不支持按任意路径读取，只认服务端已登记的 `ImageAttachmentRef`，此路不通。
- `workspaceFiles/readBytes` 已在 `HARNESS_REMOTE_ALLOWLIST`，bridge 会把二进制转成 base64 的 `value.data`，可复用——这是零协议改动的路线。

## 方案
- `TextSegments.kt`：`MarkdownInline` 加 `imagePath` 字段；`parseInline()` 新增 `![` 分支，解析 `![alt](path)`，仅当 `isWorkspaceImagePath(raw)` 成立（简单相对路径、图片扩展名、拒绝绝对/盘符/反斜杠/`..`/控制字符/超长）时填 `imagePath`，http(s) 图片不自动下载、按普通文本。
- `Attachments.kt`：新增 `loadWorkspaceImage(transport, sessionId, path)`，走 `workspaceFiles/readBytes`，workspaceFileScopeId = 会话 id，path = 相对文件名，range = `{offset:0, length:2MiB}`。
  - **关键契约 1**：`HarnessTunnel.call` 内部已把 args 塞进 `payload.args`，传给它的 args 必须是**顶层字段**，不能再手包一层 `args`，否则 host 的 `requestArgs()` 取不到 `workspaceFileScopeId`，报 `WORKSPACE_UNAVAILABLE`。
  - **关键契约 2**：host 的 `readBytes` 对 `length` 参数有 **2 MiB（2097152）硬上限**，`length = 4 MiB` 会报 `4194304 bytes exceed the 2097152 byte cap`；bridge 入口虽放行 4 MiB，底层 host 更严。故 length 取 2 MiB。
  - 返回 `value.data`（base64）经 `decodeImageData` 校验格式/大小后得到字节。
- `SessionChat.kt`：`SessionApi` / `SessionChatController` 加 `loadWorkspaceImage`。
- `MarkdownContent.kt`：`MarkdownContent` 加 `onLoadImage` 回调；`MarkdownText` 把含 `imagePath` 的 spans 切成 runs，图片路径渲染为 `WorkspaceImage`（`produceState` + `Dispatchers.IO` 异步加载，Loading 占位块 / Failed 降级显示 alt 并 `Log.w` / Ready 用 `BitmapFactory` 解码显示）。失败态打日志，避免静默失败。
- `ChatScreen.kt`：注入 loader `chat.loadWorkspaceImage`，`AssistantMessage` → `MarkdownContent(onLoadImage)`。

## 验收
- 单测：`MarkdownInlineTest` 新增 6 例（相对图片名放行 / 遍历-绝对-盘符-非图片拒绝 / 手抄报格式解析成 imagePath / 周边文本不丢 / http 与不安全路径不加载 / 链接与加粗不受影响）；全量 **26/26** 通过（FolderStore 5 + MarkdownInline 6 + SessionGroups 15）。
- 编译：`:app:assembleDebug -PdevInstall` BUILD SUCCESSFUL。
- 真机（dev 包 `com.zl.ruyiai.yuan.dev` 0.1.52 / versionCode 53，装于 `33325df` / `24122RKC7C` / REDMI K80 Pro / Android 17，USB 5038 端口）：
  - 连接「大苹果」，进入「周建」远程会话；
  - 手抄报消息由 `![...](...)` 文本变为真实图片：`ImageView` content-desc =「书香伴我成长手抄报」、bounds `[55,2994][1385,3200]` 铺满宽度；
  - alt 文本节点数归零，`MarkdownContent` 失败日志数为 0，logcat 见 `JpegXmCodec` 解码日志。
- 排错过程（均已修）：① args 多包一层 → `WORKSPACE_UNAVAILABLE`；② length 4 MiB 超 host 2 MiB 硬上限 → `4194304 bytes exceed the 2097152 byte cap`。

## 影响与回滚
- 仅改 ruyuan 工作树 `wt-folder-model`（分支 `folder-model`），零协议改动，不碰 relay / host / 正式版。
- Markdown 图片仅识别简单相对路径，不引入 WebView、不加载外部资源，保持原有纯本地约束。
- 回滚：移除 `imagePath` / `WorkspaceImage` / `loadWorkspaceImage` 相关改动即可；无持久化、无数据结构迁移。

## 状态
done（代码 + 26/26 单测 + 编译 + 真机验证均通过；**未提交、未推送**，等用户最终验收后单独授权提交）。
