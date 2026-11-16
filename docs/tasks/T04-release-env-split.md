# T04 · 发布技能区分生产/开发环境

| 字段 | 内容 |
| --- | --- |
| 任务ID | T04 |
| 目标 | 让 `mika-android-release` 用一次显式选环境决定「构建类型 / 签名要求 / OSS 落点 / 默认渠道状态 / 目标库」，彻底消除生产与开发混用 |
| 状态 | doing |
| 责任人 | 待确认（用户） |
| 提交日期 | 2026-11-16 |
| Commit ID | 未提交（暂无 Commit ID） |
| 更新时间 | 2026-11-16 |

## 目标与验收标准

- 目标：`release.mjs` 必须显式接收 `--env dev|prod`，并按环境矩阵编排；`SKILL.md` 把两条链路分开写清楚。
- 验收标准：
  1. 不传 `--env` → 退出码 2，拒绝执行（不允许存在默认环境）。
  2. `--env prod` 缺 `APPHUB_PROD_BASE_URL` → 退出码 2；脚本内不含真实生产域名。
  3. `--env prod --dry-run` 打印环境横幅：目标库、构建类型、登记渠道/状态、OSS 对象键，均取 prod 档。
  4. `--env dev --dry-run` 打印 dev 档：`127.0.0.1:3100` / perf / beta+draft / `ai/mika/android/dev/`。
  5. `--env prod` 不带 `--yes` → 退出码 2；带 `--yes` 才进入构建。
  6. `--env prod --url http://127.0.0.1:…` → 退出码 2（内网直链拦截）；同参数在 dev 下放行。
  7. `--env prod` 构建后校验签名 CN，不符即失败（不回退调试签名上线）。

## 范围

### 本期做

- 重写 `.trae/skills/mika-android-release/scripts/release.mjs`：环境 profile、地址/账号分环境解析、prod 闸门、内网直链拦截、`apksigner` 签名校验、按环境推导产物目录。
- 重写 `.trae/skills/mika-android-release/SKILL.md`：环境矩阵、两套命令、分环境环境变量表、生产发布检查清单、故障排查补充。

### 本期不做

- 不改 `app/build.gradle.kts`（版本事实源、splits、签名回退逻辑保持原样）。
- 不改 app-version-hub 任何代码与生产部署。
- 不解包 APK 回读内嵌 `BuildConfig` 地址做交叉校验（见「已否决方案」）。
- 不去掉 `APPHUB_CLI` 的本地绝对路径默认值（跨机使用需显式设 `APPHUB_CLI`）。

## 方案要点

- `PROFILES` 常量表定义 dev/prod 两档，一次覆盖六个维度，避免多处 if 分支漂移。
- 地址解析：dev 用 `APPHUB_BASE_URL`（默认 `http://127.0.0.1:3100`，**不借用账号文件里的生产地址**）；prod 三级取值 `APPHUB_PROD_BASE_URL` → `--account` 账号文件 → 扫 `~/.config/apphub/agents/*.env`（一致才采用，出现多值即报错），全部落空才退出码 2。
- 账号解析：`APPHUB_PROD_USERNAME` / `APPHUB_PROD_PASSWORD` → `--account` 账号文件 → `APPHUB_*` 回落，便于两个库的凭证同时存在。
- 账号文件：`~/.config/apphub/agents/<账号>.env`（600，`export APPHUB_BASE_URL/USERNAME/PASSWORD` 三行），这是 app-version-hub `TASKS.md` 第 192 行记录的既有落点，直接复用，不在仓库里重复存生产地址。
- 构建注入与登记目标同源：`-PapphubBaseUrl="${APPHUB_BASE_URL}"` 与 apphub 客户端使用的 `APPHUB_BASE_URL` 是同一个变量，从机制上保证「包内检查更新地址」= 「登记目标库」。
- 产物路径按 `profile.buildType` 推导：dev 在 `apk/perf/`、prod 在 `apk/release/`。

## 关键决策与取舍

1. **`--env` 必填、不设默认**：默认值无论选哪边都会在另一方向造成事故（默认 dev → 忘传就发不出去；默认 prod → 忘传就把调试包打进生产）。强制显式是最小风险路径。代价是旧命令不再兼容，属有意破坏。
2. **生产域名不内置**：与 `app-version-hub` 全部部署文档的占位符脱敏惯例一致；代价是首次使用需 export 一次。
3. **dev 用 `perf -PsideInstall` 而非 debug**：perf 不可调试（与正式包同样预编译，性能可信）、`.perf` 后缀可并排安装、可覆盖安装保留数据，比 debug 更适合作为「开发/联调」交付形态。
4. **dev 的 OSS 落 `ai/mika/android/dev/`**：与生产物理分目录，`--force` 同名覆盖只可能发生在同环境内。
5. **prod 强制 `apksigner` 校验**：`release-signing.properties` 缺失时 Gradle 静默回退调试签名，是已知会造成「装不上正式包」的坑；校验放在构建后、上传前，失败不产生任何对外副作用。

## 已否决方案

- **内置生产域名常量**：否决，与全仓库脱敏惯例冲突，且域名随技能目录进 git。
- **不传 `--env` 时按 `APPHUB_BASE_URL` 是否指向 localhost 自动推断**：否决，隐式推断在生产侧判断错误时无告警，且与「必须显式」的意图相悖。
- **解包 APK 读取内嵌地址做交叉校验**：本期不做。兜底是 `--skip-build` 时打印显式告警，由执行人确认包内地址；理由是需要额外解包工具链，收益低于当前成本。

## 阻塞 / 依赖

1. **生产 `mika-release` 账号仍未创建**：生产库需 `seed:publisher` / `init`，属生产数据变更，须单独确认后执行。
2. **OSS 凭证**：2026-11-16 已解除。`~/.ossutilconfig`（600）按 ossutil 2.x 的 `[default]` 段写入，凭证源自 `ai-server-desktop/.local/.oss-creds`（`ALIYUN_OSS_*` 变量名 ossutil 不认，需转写）。已实测：上传 → 公网域名 `https://oss-chuangxin.thchengtay.com` 下载 200 → 删除，三步全通。
3. **`release-signing.properties` 仅存在于主工作树**：新建工作树不会带过去，prod 发布会因签名校验失败而被拦下（这正是该校验的预期行为）。

## 影响评估

1、**仅改技能目录两个文件 + 台账**：不动 Mika 业务代码、不动 Gradle 配置、不动 app-version-hub，构建产物与既有发版流程不受影响。
2、**旧命令失效**：原先 `node release.mjs --changelog x` 会静默发到默认库，现在直接报错退出；如有外部脚本调用需补 `--env`。
3、**新增 `apksigner` 依赖（仅 prod）**：未安装时 prod 发布会失败并提示，属预期；可用 `APKSIGNER` 显式指定。
4、**无数据变更**：本次不写任何库、不上传任何对象、不登记版本。

## 回滚方案

- 触发条件：环境矩阵判断有误、prod 闸门误拦或漏拦。
- 执行动作：`git checkout -- .trae/skills/mika-android-release/` 还原两个文件，并在 `TASKS.md` 将 T04 置 `dropped` 并注明原因。
- 已发布版本：如需撤回，将对应版本 `releaseStatus` 改 `withdrawn`，不删库、不回退 versionCode。

## 进度快照

- 目标与验收：见上七条，1–6 已于 2026-11-16 用 `--dry-run` 实测通过；第 7 条需真实 prod 构建才能验证（当前缺签名文件与 OSS 凭证）。
- 已完成：
  - `scripts/release.mjs` 重写完成（环境 profile、prod 闸门、内网直链拦截、签名校验、产物目录分环境）。
  - `SKILL.md` 重写完成（环境矩阵、两套命令、分环境变量表、检查清单、排查表）。
  - `TASKS.md` 新增 T04 行。
- 实测记录：
  - 缺 `--env` → exit 2；prod 缺地址 → exit 2；prod 无 `--yes` → exit 2；prod 内网直链 → exit 2；dev 同参数放行。
  - dev dry-run：`127.0.0.1:3100` / `perf` / `beta`+`draft` / `ai/mika/android/dev/mika-0.1.48-universal.apk`。
  - prod dry-run：生产地址 / `release` / `official`+`published` / `ai/mika/android/mika-0.1.48-universal.apk`。
- 未验证：真实 gradle 构建产物上传 + 生产库登记（阻塞项 1）。
- 补验（2026-11-16 13:05）：`~/.ossutilconfig` 落盘（600，字段 `accessKeyID`/`accessKeySecret`/`region`/`endpoint`）；
  `ossutil cp` 上传探测对象 → `curl https://oss-chuangxin.thchengtay.com/...` 返回 200 `text/plain` → `ossutil rm` 删除 →
  前缀回到 0 对象。另实测环境变量名：仅 `OSS_ACCESS_KEY_ID`/`OSS_ACCESS_KEY_SECRET` 生效，
  `ALIBABA_CLOUD_*`、`ALICLOUD_*` 均报 `Credentials is null or empty`。
  脚本已补 `OSS_CONFIG_FILE`（转 `--config-file`）与构建前凭证前置检查。
- 补验（2026-11-16 13:12）：生产地址改为可从项目既有落点自动获取——`~/.config/apphub/agents/{agent-01,02,03}.env`
  三份文件 `APPHUB_BASE_URL` 一致（`https://ai.thchengtay.com/apphub`），脚本在未显式给地址就直接采用；
  `--account agent-01` 可同时取到地址与账号。实测：prod 自动发现命中、`--account` 命中、dev 仍走 `127.0.0.1:3100`
  （账号文件里的生产地址不参与 dev）、账号名不存在时列出可用账号。
- 待验证：`agent-01/02/03` 是否能登录生产、是否拥有 `com.zl.ruyiai.yuan`（未跑生产登录，需用户授权选定账号）。

## 收尾（2026-11-16 14:36，验收全部达成）

- **验收第 7 条已过**：`--env prod --account agent-01 --yes` 真实跑通全链路——构建（release，签名 `CN=CN=Yuan AI, OU=Mobile, O=XXT`）→ OSS `ai/mika/android/mika-0.1.48-universal.apk` → 生产库建应用 `com.zl.ruyiai.yuan`（id=6，owner agent-01）→ 登记 `0.1.48 / code 49 / android·official·published`（version id=3）。
- **账号问题解决**：`agent-01/02/03` 三个都能登录生产（role=publisher），但应用 owner 是 agent-01，换账号发会 404（`findOwnedApp` 按 owner 过滤）。
- **三项外部验收**：`update/check?currentVersionCode=48` → `hasUpdate=true` `policy=optional`；`versions` → total=1 `published` `rolloutPercent=100`；OSS 直链 HEAD → 200 `application/vnd.android.package-archive` 132,555,025 B。
- **真机安装验证**：Redmi `24122RKC7C`（`33325df`）卸载旧 debug 包后装正式包 `Success`，`pkgFlags` 中 `DEBUGGABLE`/`TEST_ONLY` 消失、签名哈希变化、`MainActivity` 正常前台无崩溃。
- **已知偏差**：`BETA=true` 未改（关于页显示 Beta），留待下个版本。
- **提交**：本次改动（技能两文件 + 本文件 + `TASKS.md` 状态置 `done`）提交于 `61d03b60`（2026-11-16），未 push；`app/release-signing.properties` 因 gitignore 未入库（本机文件，权限 600）。

## 二次发版（2026-11-16 14:50，0.1.49 / code 50）

- **触发**：用户要求「上传 OSS + 更新发布平台版本信息，整个链路完整走一遍」。0.1.48 已在 14:31 上线，原样重跑会撞 `apphub-publish.mjs:214` 的 versionCode 冲突，故升版。
- **改动**：`ruyiVersionName="0.1.49"`、`ruyiVersionCode=50`、`BETA=false`（补上 0.1.48 遗留的「关于页显示 Beta」偏差）。
- **结果**：version id=4、`official/published`、sha256 `268e6966…6cf60`；三项验收同 0.1.48 全过（`versions` total=2、`check?currentVersionCode=49` → `hasUpdate=true`、OSS HEAD 200）。
- **升级提示的真实形态**（本次查清，此前误以为有启动弹窗）：无启动弹窗。`UpdateChecker.autoCheckInBackground()` 在 `MainActivity.onStart()`（第 290 行）静默查一次，2 分钟内不重查；结果只落两处——侧栏「关于如愿」红点（`AboutCopy.drawerDot`）与关于页「检查更新」行「有新版本 / x 可更新」（`AboutCopy.checkValue`）。自动下载另需用户手开「自动更新」且连 Wi-Fi（`AutomaticUpdates.enabled` 默认 `false`）。
- **真机闭环**：机上 0.1.48 出现 27×27px 红点节点 → 关于页「0.1.49 可更新」→ `adb install -r` 覆盖安装（同签名无需卸载）→ 红点消失、关于页 `版本 0.1.49` 且 **Beta 标记消失**、`检查更新 / 已是最新`。
- **文档笔误修正**：真实 `applicationId` 与 `MikaLinks.APP_KEY` 均为 `com.zl.ruyiai.yuan`（单 i），本技能 `SKILL.md` 与 T01/T02 正文写成双 i（`com.zl.ruiyiai.yuan`）。双 i 键打 `update/check` **不报错、只静默返回 `hasUpdate=false`**，排查「发了没提示」时极易被带偏，全部 10 处已纠正。
