# T07 · 网络恢复后自动补连（onAvailable）

- **任务ID**：T07
- **状态**：doing
- **更新时间**：2026-11-16
- **责任人**：实现 AI，验收 binny
- **关联**：T06（唯一在线机自动连接），痛点来源：T06 讨论中发现的断网自愈缺口

## 一、目标

手机断网时间**超过自动重连窗口（约 82s）**后，即便网络恢复，原先也停在「与 X 的连接已断开，需要重新连接」，必须用户手动点重连。本任务让网络一恢复就自动补连一次上次连过的电脑，把手动重连降级为最后兜底。

## 二、范围

### 本期做

- `RemoteHome.kt`：`NetworkCallback` 增加 `onAvailable`，post 回主线程调 `session.onNetworkAvailable()`。
- `RemoteSession.kt`：新增 `reconnectTarget`（上次连过的机器）与 `onNetworkAvailable()`；`connect()` 记目标，`disconnect()` / `clearSession()` 清目标。

### 本期不做

- 不改中继协议、不改心跳参数（25s×3）。
- 不做「连接失败后的自动重试」（失败态仍只给重试入口）。
- 不做 `applySnapshot` 的空洞检测（快照窗口外的历史缺口，单独立项）。

## 三、方案要点

| 决策点 | 结论 | 理由 |
| --- | --- | --- |
| 触发 | `ConnectivityManager.onAvailable` | 这是系统层唯一可靠的「网络回来」信号；`onLost` 已用于判死 |
| 连谁 | `reconnectTarget`（上次 `connect()` 的 host） | 掉线后 `connectedHost` 已清空，不能再依赖它 |
| 拦截条件 | 未登录 / 已抑制（主动断开） / 已有连接或在连 / `connectionError != null` | 覆盖「主动断开不该被拉回」「失败态不该被网络抖动反复重试」 |
| 与退避循环 | 允许并行触发，靠 `ConnectionEpoch` 让旧循环退出 | 立即连上比等下一轮退避（最长 30s）体验好，且不会产生两条链路 |

## 四、验收标准

1. 连上电脑 → 开飞行模式 2 分钟（超过 82s 窗口，界面出现「需要重新连接」）→ 关飞行模式：**自动重连成功**，无需手动点击。
2. 手动点「断开」→ 切换网络（Wi-Fi ↔ 蜂窝）：**不会**被自动连回。
3. 连接因非网络原因失败（如中继 4xx）：切换网络**不会**触发自动重试。
4. 已正常连接时切换网络：不产生额外连接动作（控制器内 no-op）。
5. 编译：`:ruiyi-remote-ui:assembleDebug` 与 `:app:compileDebugKotlin` 通过。

## 五、阻塞 / 依赖

- 无。真机验证需至少一台在线电脑与可控的网络开关（飞行模式即可）。

## 六、影响评估

- 改动文件 2 个：`RemoteHome.kt`、`RemoteSession.kt`；无接口 / 数据结构 / 权限变更。
- 行为变化：网络恢复时可能自动建立一条链路，中继侧会多一次 `connect` 记录（预期）。
- 风险：`registerNetworkCallback` 注册瞬间会回调一次 `onAvailable` → `reconnectTarget` 为 null，实测为 no-op，不影响冷启动。
- 测试缺口：无自动化测试，依赖真机手测。

## 七、回滚方案

- 删除 `RemoteHome.kt` 的 `onAvailable` 重写即可完全恢复原行为；`reconnectTarget` 与 `onNetworkAvailable()` 成为死代码，不影响运行。
- 或整批 `git revert`。

## 八、进度快照

- 2026-11-16：用户确认「补 onAvailable 钩子」方案，实现完成。
- 编译验证：`:ruiyi-remote-ui:assembleDebug` + `:app:compileDebugKotlin` BUILD SUCCESSFUL。
- 2026-11-16 真机验收（Android 17 日常机，覆盖安装后手测）：
  1. 飞行模式 90s+（超过 82s 退避窗口）后关闭 —— **通过**。界面先退到「还没有连接到电脑」，
     恢复网络后日志 `RemoteSession: 网络恢复，重连 大苹果`，抽屉头变回「当前连接 · 在线」，会话列表 30 条重新拉回。
  2. 手动断开后切网络 —— **通过**。`onLost` 有触发（`RuyiRemoteHome: 承载网络丢失且无替代…`），
     但控制器 `connection == null` 直接返回；网络恢复后无任何重连日志，界面仍停在「在线，可以连接」。
  3. 连接失败后切网络 —— **通过**。断网点在线机得到
     `连接 Ai-Host失败：网络不通或中继暂时不可用。（CONNECTION_FAILED）`（此时 `reconnectTarget`
     已被 `connect()` 写成 Ai-Host，若守卫失效必然重试）；恢复网络后重连日志 0 条，界面保持失败态 +「重新连接」。
  4. 已连接状态关 Wi-Fi（蜂窝仍在）—— **通过**。默认网络切到蜂窝，无判死日志，界面保持「当前连接 · 在线」。
- 附带发现：抽屉里离线电脑行是 `enabled = host.online`，点它不会发起连接，无法用此路径构造失败；
  失败态改用「先断网再点在线机」构造。
- 模块无 test 源集，未做单元测试。
