# Android 语音引擎排查与修复命令手册

- **文档类型**：故障排查与运维修复
- **操作人**：AI (Trae)
- **原代码负责人**：Mika 项目既有作者
- **提交日期**：2026-11-24
- **Commit ID**：Commit ID 未提交（暂无 Commit ID）

---

## 1. 现象与核心根因

### 常见现象
1. **按了没反应，界面无任何提示**：在对话输入框底部长按语音输入按钮，没有转出文字，界面甚至没有弹出任何错误卡片。
2. **误导性提示**：界面提示「麦克风用不了，可能正被别的 App 占着」，但实际上后台没有任何其他应用在录音。

### 核心根因
Android 系统的原生语音识别入口（`SpeechRecognizer.createSpeechRecognizer(context)`）强依赖系统级的**默认识别服务**配置（`voice_recognition_service`）。
若系统设置中的该项为 `null`（常见于定制系统新机初始化、刷机精简 ROM、或清除语音助手数据后），系统识别服务初始化阶段即被阻断，App 内部甚至连音频录音器（`AudioRecord`）都无法创建。

---

## 2. 快速排查命令清单

执行以下命令前，请定义目标设备编号（例如通过 `adb devices` 获取）：
```bash
D=kf95555prgi79txk # 替换为实际设备序列号，单设备可省略 -s $D
```

### 2.1 检查系统默认语音识别服务（首要排查点）
```bash
# 读取系统默认识别服务（若返回 null 则为本问题典型特征）
adb -s $D shell settings get secure voice_recognition_service
```

### 2.2 探测手机上可用的识别引擎服务
```bash
# 查询系统内声明了 RecognitionService 的全部服务
adb -s $D shell "cmd package query-services -a android.speech.RecognitionService"
```
> **注**：在 Android 16+ 固件中，`cmd package resolve-service` 已被移除，必须使用 `query-services -a android.speech.RecognitionService`。

### 2.3 检查麦克风权限与系统静音状态
```bash
# 检查如意 AI / Mika 的录音权限
adb -s $D shell dumpsys package com.zl.ruyiai.yuan | grep -E "RECORD_AUDIO: granted"
adb -s $D shell appops get com.zl.ruyiai.yuan RECORD_AUDIO

# 检查系统全局麦克风是否被静音
adb -s $D shell dumpsys audio | grep -i "mic mute"
```

### 2.4 检查麦克风是否真正被占用
```bash
# 检查当前音频通道活跃情况与最近录音事件
adb -s $D shell "dumpsys media.audio_flinger | grep -i -E 'standby|active'"
adb -s $D shell "dumpsys audio | grep ' rec ' | tail -10"
```

### 2.5 捕获识别初始化与按压日志
```bash
# 清空日志后长按说话复现，抓取关键异常
adb -s $D logcat -c
# 复现操作后执行：
adb -s $D logcat -d -v time | grep -i -E "SpeechRecognizer|RecognitionService|AudioRecord|no selected voice"
```
若日志中输出 `E/SpeechRecognizer: no selected voice recognition service`，即可 100% 确认是默认服务缺失。

---

## 3. 修复与配置命令

### 3.1 核心修复：显式指定系统默认识别引擎

以小米/红米设备自带的小爱语音识别引擎（`com.xiaomi.mibrain.speech`）为例：

```bash
# 1. 写入默认识别服务
adb -s $D shell settings put secure voice_recognition_service com.xiaomi.mibrain.speech/.asr.AsrService

# 2. 回读验证是否写入成功
adb -s $D shell settings get secure voice_recognition_service
# 预期输出：com.xiaomi.mibrain.speech/.asr.AsrService
```

### 3.2 重启应用使设置即时生效
```bash
# 强制停止如意 AI（或 com.wangzan101.mika）
adb -s $D shell am force-stop com.zl.ruyiai.yuan

# 重新拉起应用主界面
adb -s $D shell am start -n com.zl.ruyiai.yuan/com.wangzan101.mika.ui.MainActivity
```

---

## 4. 引擎深度损坏时的自愈修复（可选）

若系统引擎出现僵死或零响应，可按以下流程对系统语音引擎做数据重置与权限重建：

```bash
# 1. 清理语音引擎缓存与数据
adb -s $D shell pm clear com.xiaomi.mibrain.speech

# 2. 强制停止引擎进程
adb -s $D shell am force-stop com.xiaomi.mibrain.speech

# 3. 重新授予录音权限
adb -s $D shell pm grant com.xiaomi.mibrain.speech android.permission.RECORD_AUDIO
adb -s $D shell appops set com.xiaomi.mibrain.speech RECORD_AUDIO allow

# 4. 重新补齐默认识别服务设置（清数据后会被重置为 null，务必补齐）
adb -s $D shell settings put secure voice_recognition_service com.xiaomi.mibrain.speech/.asr.AsrService
```

---

## 5. 云端识别兜底配置

若系统本地语音引擎确实无法恢复（或缺少中文语言模型包），可在应用内直接切换为云端语音识别引擎，完全绕开系统本地识别服务：
1. 打开应用左侧抽屉，点击 **「设置」→「模型」→「语音转文字」**；
2. 在「边说边转的语音服务」中，选择 **Fun-ASR 实时语音识别** 或 **豆包 / 百炼**；
3. 填入对应的 API Key 与地域模型，即可实现高可靠识别。
