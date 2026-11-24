# 远程亮屏与解锁验证

日期：2026-10-31。基于 main 提交 `65e82c0d01ac0f016db76cde4c76cb9a3c03183c`（0.1.16）。

## 范围与分层

- 手机端默认关闭远程亮屏/解锁，凭据只能在电脑连接设置页本地配置。
- `core/device/DeviceAccess.kt` 负责串行准备流程和实际状态核验；平台输入、识别由 Android 适配提供。
- `app/settings/DeviceAccessSettings.kt` 使用独立 Keystore 密钥加密原子存储，保留非秘密状态，不缓存解密后的凭据。
- `McpDeviceTools` 提供状态、亮屏、解锁接口，拒绝所有参数，沿用原 MCP 认证；不进入模型工具目录或任务历史。
- `TaskGate` 保持原有逻辑，真实解锁后自动放行。主屏设备管理独立于 Agent 虚拟屏。
- 设置页使用现有分组、行、按钮与输入组件，中英文资源对应；不恢复或显示旧凭据，编辑时设置禁止截图标志。

## 自动验证

构建环境：JDK 17、Android SDK 36、仓库现有 Gradle/AGP/Kotlin 版本。

```sh
./gradlew :lib-core:test :app-main:testDebugUnitTest \
  :app-main:assembleDebug :app-main:assembleDebugAndroidTest
```

最终结果：core 2416 项、app JVM 977 项，无失败、无错误；core 中 2 项原有测试跳过。涵盖依赖方向、文档一致性、中英文资源、原有回归及新增功能测试。

新增验证覆盖：默认关闭、无助手、首次解锁边界、幂等亮屏、命令成功但实际未亮屏、无凭据锁屏、PIN/密码流程、输入后擦除、错误凭据后持久暂停、并发调用、取消、等待时手动解锁/撤销许可、加密存储与密钥丢失、凭据更新使旧请求失效、MCP 参数/认证限制、原版锁屏自绘 PIN 控件及 SIM/未知控件排除。

## 安卓 16 测试机

通过独立的 Mika Dev（应用标识 `com.wangzan101.mika.dev`，本地改了应用标识的调试包，不在本次改动内）验证，原发行版及其数据保留。开发版 USB MCP 使用独立端口 18765；没有复制原版模型 Key 或其他私有数据。

用户只在手机上配置真实锁屏凭据，测试脚本未读取或输出凭据。记录只包含以下非秘密状态：

| 阶段 | interactive | keyguard_showing | device_locked | ready |
| --- | --- | --- | --- | --- |
| 开始 | true | false | false | true |
| 熄屏并锁定 | false | true | true | false |
| 远程亮屏返回 awake | true | true | true | false |
| 远程 PIN 解锁返回 unlocked | true | false | false | true |

提交前的持久占位被监测到，成功后占位清除，证明实际走过凭据提交路径，而非只检测到已解锁状态。

安卓原版锁屏的 `pinEntry` 是自绘的 `com.android.keyguard.PasswordTextView`，不声明 editable。识别规则按原版的 ID、类名和密码标志放行，其余只认带密码标志、输入类型匹配的编辑框，并有回归测试；未按坐标盲点，也未读取控件文字。

`DeviceAccessSectionTest` 在真机通过：二次输入确认、保存条件和保存后不显示输入值。测试仅使用独立缓存中的模拟数据，没有覆盖真实配置。首次测试页未进入前台，终止后从开发版前台重新运行，结果为 OK（1 test）。验证后移除测试包。

## 明确未验证的部分

- 真机验证使用用户现有 PIN；没有更改手机的锁屏类型。因此无密码/滑动和密码场景有自动测试覆盖，尚未在该真机上单独切换验证。
- 密码支持 4–64 位可打印 ASCII 字符，PIN 为 4–16 位数字；不支持图案、SIM PIN 或未知的厂商凭据页。
- 不支持重启后首次解锁前启动现有 Mika MCP。
- 上面的真机记录早于「凭据按键只定向投给 SystemUI 窗口」（`ShellService.injectTo`）这一改动；改动后需在真机上重跑 PIN 解锁，并验证锁屏上方有其他窗口时返回 `input_failed` 且不输入。
- 没有故意向真机提交错误 PIN；失败后停止重试由自动测试核验。
- 没有变更原有任务付款/确认策略，也没有为验证配置模型或执行对外 App 任务。

手机使用步骤见 [电脑连接说明](../mcp.md)。
