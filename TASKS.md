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
