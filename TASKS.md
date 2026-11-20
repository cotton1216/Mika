# TASKS · Mika 任务台账

> 状态单一事实源：每任务一行。方案详情见 `docs/tasks/` 下对应独立文档。

| 任务ID | 目标 | 状态 | 方案文档 | 更新时间 |
| --- | --- | --- | --- | --- |
| T01 | Mika 安卓一键发布技能（构建→OSS→apphub 登记→回读） | doing | [docs/tasks/T01-mika-android-release.md](docs/tasks/T01-mika-android-release.md) | 2026-11-15 |
| T02 | 更新来源从 GitHub Releases 切到自建平台 apphub（检查更新/下载/安装） | doing | [docs/tasks/T02-mika-update-source.md](docs/tasks/T02-mika-update-source.md) | 2026-11-15 |
| T03 | 如愿 ↔ 如意 共享登录闭环（退出联动 + 注册失败可观测） | done | [docs/tasks/T03-ruyi-remote-sso-linkage.md](docs/tasks/T03-ruyi-remote-sso-linkage.md) | 2026-11-16 |
| T04 | 发布技能区分生产/开发环境（`--env dev\|prod` 矩阵 + 生产闸门 + 签名校验） | done | [docs/tasks/T04-release-env-split.md](docs/tasks/T04-release-env-split.md) | 2026-11-16 |
| T06 | 如意抽屉默认展开电脑列表 + 唯一在线机自动连接 | done | [docs/tasks/T06-ruyi-drawer-default-host.md](docs/tasks/T06-ruyi-drawer-default-host.md) | 2026-11-16 |
| T07 | 网络恢复后自动补连（onAvailable），断网超窗口不再停在断开页 | done | [docs/tasks/T07-ruyi-network-recovery.md](docs/tasks/T07-ruyi-network-recovery.md) | 2026-11-16 |
| T08 | 抽屉有列表即收起 + SVG 渲染 + 失败重试 + 模型弹窗关闭 icon | doing | [docs/tasks/T08-ruyi-ux-polish.md](docs/tasks/T08-ruyi-ux-polish.md) | 2026-11-16 |
| T09 | 更新事实源从 apphub 接口切到自研静态 feed（desktop 同域托管） | done（代码 + feed 0.1.52 已发布；真机回归未做） | [docs/tasks/T09-apk-static-feed.md](docs/tasks/T09-apk-static-feed.md) | 2026-11-19 |
| T10 | 安卓端展示品牌统一为「如意 AI」，保留技术标识及数据兼容性 | done（源码与构建；全量 core 文档检查阻塞） | [docs/tasks/T10-ruyi-ai-brand.md](docs/tasks/T10-ruyi-ai-brand.md) | 2026-11-18 |
| T11 | Remote 会话文件夹模型与本地持久化（数据层，UI 分组下一阶段） | done（代码 + 5 单测 + 编译通过；未提交；真机 I/O 未验） | [docs/tasks/T11-folder-model-store.md](docs/tasks/T11-folder-model-store.md) | 2026-11-19 |
| T12 | Remote 会话列表分组渲染（文件夹 + 按天；只做展示，不接管理 UI） | done（代码 + 6 单测 + 编译通过；未提交；真机按天折叠已验） | [docs/tasks/T12-session-grouping-ui.md](docs/tasks/T12-session-grouping-ui.md) | 2026-11-19 |
| T13 | Remote 分组维度可切换（天/状态/目录；文件夹常驻顶层 + 维度落盘） | done（代码 + 5 单测 + 编译通过；dev 包已装机；待用户复验；未提交） | [docs/tasks/T13-group-dimensions.md](docs/tasks/T13-group-dimensions.md) | 2026-11-19 |
| T14 | Remote 第四分组维度「按群」（解析 `成员名 · 群名`；≥2 成员成群；零协议改动） | done（代码 + 4 单测 + 全量 20/20 + 编译通过；dev 包已装机，真机六成员聚合/折叠/私聊不误伤已验；未提交） | [docs/tasks/T14-group-by-room.md](docs/tasks/T14-group-by-room.md) | 2026-11-19 |
| T15 | 手机端 Markdown 图片按工作区路径拉图（修手抄报不显示；零协议改动） | done（代码 + 6 单测 + 全量 26/26 + 编译通过；dev 包已装机，真机手抄报由文本变真实图片已验；未提交） | [docs/tasks/T15-markdown-workspace-image.md](docs/tasks/T15-markdown-workspace-image.md) | 2026-11-19 |
| T16 | 群聊合并时间线 MVP（同群多成员会话合并成单一时间线 + 发言人 + 发给谁；零协议，本地合并非实时房间） | done（代码 + 10 单测 + 全量 36/36 + 编译通过；dev 包已装机，真机 6 成员合并/发言人/目标切换已验；未提交） | [docs/tasks/T16-group-merged-timeline.md](docs/tasks/T16-group-merged-timeline.md) | 2026-11-19 |
| T17 | 群聊体验补齐（发给谁下拉选 + 不指定成员广播全体 + 全会话页同维度分组 + 群聊页图片/SVG/MD 富文本补齐 + 多群专家诊断） | 代码完成 + 单测 43/43 + 编译通过（dev APK 就绪）；下拉/广播/全会话页分组此前已装机见效果，**富文本补齐 + 全会话页精确复验待设备恢复后装机**；未提交 | [docs/tasks/T17-group-chat-polish.md](docs/tasks/T17-group-chat-polish.md) | 2026-11-19 |
