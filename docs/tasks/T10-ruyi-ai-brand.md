# T10 · 安卓端展示品牌统一为「如意 AI」

| 项目 | 说明 |
| --- | --- |
| 责任人 | 需求与验收：用户；协调复核：team-lead；非 XML 实施与测试：general-purpose-1；XML 实施：general-purpose-2。原代码负责人以 Git 历史为准。 |
| 更新时间 | 2026-11-18 |
| 进度 | 展示更名、排版空格、顶栏 Tab 英文、技术自标识、现有相关测试与 Debug/Release 构建完成；全量 core 测试仍被历史文档引用检查阻塞；手机未安装、未验收。 |
| 优化对象与基线 | 分支 ruyuan，HEAD e2128b8c。旧品牌出现在 Android 多语言资源、运行时文案、模型提示、电话默认值和 APK 文件名。初始工作区已有 T08 修改和未跟踪 T05 文档，本任务不触碰。 |
| 目标与前后对比 | 用户看到的「如愿」改成「如意 AI」（一个半角空格），包括所有语言、通知、模型提示、电话问候及本地 APK 文件名；开发/测试/日志变体分别使用「如意 AI Dev」「如意 AI 测试版」「如意 AI Logs」。不重复追加 AI。 |
| 编号说明 | 初建时误用 T09；跨工作树已有同编号发布任务，复核本工程 T10 空闲后更正为 T10，不改他人记录。 |
| 变更归属 | 用户已确认展示文案及既有相关断言、金样同步；本次仅修改本地工作树。未提交（暂无 Commit ID）。 |
| 本期不做 | 不改 applicationId、namespace、签名、版本、协议、更新键、OSS 前缀、工程目录；不迁移存量数据，不覆盖自定义问候；不提交、推送、发布或安装。 |
| 依赖 | 已完成 XML 更名并通过 530 份 XML 解析；使用指定 JBR 21 与 Android SDK，Release 显式注入原 apphubBaseUrl。 |
| 影响评估 | 无数据库结构、事务、权限或安全策略调整。仅识别两代已知整句出厂问候，其他存储值原样返回；保持应用身份及更新链路不变。 |
| 验证与回滚 | 检查多语言文本、兼容残留、既有相关测试、APK 构建和 aapt/apksigner；出现本任务导致的不兼容时，仅撤回本任务差异，不动他人工作或用户数据。 |

## 主要变更及兼容例外

- 同步运行时文字、默认系统身份、工具展示 label、登录显示名、通知及电话默认问候。工具协议 name/id 不变。
- Gradle 和发布脚本同步本地 APK 名；远程 OSS 对象键与更新契约不变。脚本仅语法检查，未执行发布。
- 电话默认问候同时识别「您好，我是如愿的 AI 电话助手。请问有什么需要我转告？」与「您好，我是如愿 AI 电话助手。请问有什么需要我转告？」，映射为新默认；自定义旧名文本和显式空白保持原行为。
- 有意保留的用户可见旧名：Download/如愿、Download/如愿 Browser 等历史目录及对应 JSON/测试/提示词路径，避免文件失联。
- MCP 客户端展示名与注册名、剪贴板识别标签、WakeLock 标签同步改为新品牌：MCP 服务端会登记新的 client_name（功能不变，仅登记名变化）；升级后无法再识别旧版本写入剪贴板的「如愿」内容（仅影响旧剪贴板条目的恢复识别）。
- 仍保留的兼容标识：ADB 证书 CN、遥测 service.name/span 名、配对设备名 mika@、协议参数键。改了会破坏已配对设备或外部统计口径。
- Mika 的包名、类名、资源键、Theme、CLI、真实仓库 URL、设备标识保留；「如您所愿」等正常词语不改。
- 顶部两个 Tab 的用户可见标签按用户指定改为功能性英文 Chat / Remote（枚举、返回栈与行为不变），注释同步；聊天页标题仍为「如意 AI」。
- 「如意 AI」后紧跟汉字处统一补一个半角空格（含注释、日志与用户可见文案）；日语资源按日文排法有意不加。全仓复检非日文缺空格为 0、无「如意 AI AI」与多空格。
- 旧注释已同步改为新品牌；仅保留测试输入样本与历史文档原文。

## 当次验证证据

所有 Gradle 命令使用指定 JAVA_HOME 和 ANDROID_HOME，工作目录为本工程。快照仅在确认差异为品牌变化后，用项目自带 UPDATE_GOLDEN / -PupdateSnapshots 更新；下表数量均来自不带更新开关的最终全量复验。

| 命令 / 检查 | 当次结果 |
| --- | --- |
| UPDATE_GOLDEN=1 :core:test -PupdateSnapshots（金样重建） | BUILD FAILED：AdbIdentityTest 报 CN 由「如愿」变「如意 AI」——批量注释替换误改证书 CN，已立即改回「如愿」；提示词金样已按最终文案生成。后台任务 XifBQE。 |
| :core:test（正常模式，最终源码） | 3327 个测试，3321 通过、1 失败、5 跳过、0 error；唯一失败为历史文档引用检查；退出非零，不宣称全部通过。后台任务 GPg9eC，报告时间 2026-11-18T05:57:05。 |
| :app:testDebugUnitTest :app:assembleDebug（最终源码） | BUILD SUCCESSFUL；2179 个测试全部通过、0 失败、0 跳过；报告时间 2026-11-18T05:57:44 至 05:58:08。后台任务 Q3S4U0。 |
| :app:assembleRelease -PapphubBaseUrl=https://ai.thchengtay.com/apphub（最终源码） | BUILD SUCCESSFUL；两份 ABI 产物于 13:58:49 重新生成。后台任务 Q3S4U0。 |
| :core:test --tests '*DocsConsistencyTest'（文档收尾后） | 4 个测试，3 通过、1 历史引用检查失败，无 T10 新增问题；后台任务 UGKsqe。 |
| 正常模式提示词专项（包含在上述完整 core 中） | PromptSurfaceTest 6、SystemPromptAssemblyTest 4、SystemPromptTest 20、PromptLayoutTest 9、PromptVersionRecordTest 1，合计 40 个，全部通过。 |
| XML / 重复品牌 | 530 份 XML 解析通过；资源旧品牌归零；未检出「如意 AI AI」或「如意」与 AI 间多个空格。 |
| 脚本静态检查 | node --check 发布脚本、bash -n 资产脚本通过；未发布。 |
| APK 检查 | Debug、arm64 Release、universal Release 各 88 个 locale label 均为「如意 AI」；包名 com.zl.ruyiai.yuan，versionCode 51、versionName 0.1.50。 |
| 签名检查 | 三包 apksigner verify 退出 0（v2）；两份 Release SHA-256 同为 5c448ff71dd69fdb85cdc127ebb94a129afa29968aecd8fc38983f84ffbbdd0d；Debug 为 e5357d23633c58911c999de7c07c721d3774a3d8f979b4d982f4480df3a38e43。签名配置未变。 |

最终产物位于 app/build/outputs/apk 下：debug/app-debug.apk，以及 release 中以「如意 AI-0.1.50-」开头的 arm64-v8a-release.apk、universal-release.apk。仅用于验证，未安装、未发布。

## 未解决的全量验证阻塞

唯一失败为 DocsConsistencyTest.docsMentionOnlyThingsThatExist。报告涉及旧任务 T01/T03/T04/T05/T06/T07/T08 及远程嵌入说明中的缺失路径、跨模块类名（如 ConnectScreen、SessionDrawer、BuildConfig）与旧版本 APK 路径；不涉及本任务方案。未对未修改基线执行同一测试，因此仅记录为「历史文档引用问题」，不宣称已证明基线原有失败。未修改历史文档或放宽检查。

全局 git diff --check 仅见他人 T08 文档第 88 行 EOF 空白；排除该文件后本任务补丁格式检查通过。

## 文件统计

`git diff --stat` 末行：685 files changed（含他人 T08 文档与本次新建 T10 方案）。工程内本任务 685 个文件：684 个已跟踪修改 + 新建本方案 1 个；其中语言资源 367、其他 XML 1、测试源码 23、测试金样 39、其余 255（含运行时/提示词/台账/README 生成段/脚本等）。其余数量较前次增加，来自旧注释统一更名。负责人对 teach_skill.md 与 configure-mika/SKILL.md 的补改已复核为纯品牌差异，并用 :core:updateReadme 与 -PupdateSnapshots 跟新 README 生成段和快照。不计初始 T08 修改和未跟踪 T05。任务记忆目录三个文件单独维护，不计入工程文件数。

## 旧名保留清单（14 行）

| 位置 | 原因 |
| --- | --- |
| CallPolicy.kt:131-132 两代问候常量 | 兼容已存自定义/旧版出厂问候，行为所需。 |
| AuthenticatedBrowserDownloads.kt:150、ShareExporter.kt:29/45/74/85、SandboxPort.kt:69 | Download/如愿、如愿 Browser 历史目录与 JSON 路径，改了老文件失联。 |
| prompts/tools/share_file.md:1、prompts/capabilities/sandbox.md:11 | 同上，模型文档需与实际目录一致。 |
| TraceFiles.kt:202、RunTrace.kt:125 | 遥测 service/span 名，改了统计口径断裂。 |
| AdbIdentity.kt:281 注释与证书 CN | 已配对设备比对证书，改动会破坏连接。 |
| extract_pdf.py:2 | 版权署名，非展示品牌。 |

日语 `values-ja` 的「如意 AI内蔵」等 8 处为日文排法，未补空格，属有意保留。
