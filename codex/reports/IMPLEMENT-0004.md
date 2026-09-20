# IMPLEMENT-0004：Android M0 基础设施

## 对应需求编号

REQ-0004

## 本次实现目标

建立“异世界勇者安卓自动化助手”的 M0 Android 基础设施：仅验证权限、窗口、采集、状态机、数据模型与诊断链路；不实现副本刷取，不向游戏发送点击或手势。

## 修改文件

- Android Gradle 工程、Wrapper 与应用模块配置。
- `app/src/main/AndroidManifest.xml`、Compose 单 Activity、无障碍服务、MediaProjection 前台服务、状态机、窗口监测、Room 模型/DAO/数据库、诊断和本机配置 UI。
- `tools/pad-inspector/`、`config/device.env.example`、`diagnostics/device-sample.example.json`。

## 新增文件

- Android 应用源代码及单元/仪器测试。
- Room schema `app/schemas/com.lidquan.yishigame.data.AppDatabase/1.json`。
- Gradle Wrapper 8.9。
- Pad Inspector、其标准库测试和使用说明。

## 核心实现说明

- Compose 单 Activity 以中文平板 UI 展示权限、设备、窗口、诊断及 M0 占位状态；开始按钮仅在 `READY` 可用。
- 状态机覆盖 `IDLE` 至 `STOPPED` 全部需求状态；任意状态的 `STOP` 进入 `STOPPED`，任意 `Fail` 进入 `ERROR`，两种状态均不触发游戏操作。
- 无障碍层只提供受控骨架：连接状态、前台包名、窗口枚举、手势能力接口；业务层没有调用点击/滑动/返回。
- MediaProjection 仅捕获一帧后释放 ImageReader、VirtualDisplay 和 Projection；原始图像不写入仓库。
- Room 包含 Role、Dungeon、DailyExecution、ErrorEvent、AppSettings；DailyExecution 对日/角色/动作/目标设置唯一索引，未配置破坏性迁移。
- Target package 只有用户输入并通过格式校验后才保存到 app 私有 SharedPreferences；未在代码或报告中固化实际游戏包名。
- Pad Inspector 只允许一个 ready ADB 设备，私有报告写入 `~/.config/yishijieyongzhe/private/pad-reports/`；窗口边界优先取 WindowManager，缺失时回退到 ActivityManager 已确认任务边界。

## 关键技术决策

- 使用 Kotlin、Compose、Coroutines/Flow、Room 和 KSP；不引入额外自动化框架。
- 无障碍已启用状态同时参考服务连接、AccessibilityManager 和安全设置中的组件列表，适配该 Pad 的多实例/列表刷新差异。
- M0 截图授权测试只共享助手自身，未采集游戏画面。
- 三个游戏窗口样本只来自已确认的系统任务，并用真实边界推导 resize 请求；尝试后恢复原始边界。

## 构建结果

- `./gradlew --no-daemon testDebugUnitTest assembleDebug`：通过。
- Android SDK 命令行工具给出 SDK XML 版本提示；构建不受影响。

## 测试结果

- Kotlin 单元测试：4 项通过（状态机、窗口监测、目标包名格式）。
- Room 仪器测试：1 项通过。
- Pad Inspector 标准库测试：4 项通过。
- `python3 tools/security/check_public_repo.py --history`：通过；当前/索引 76 个文本候选、历史 53 个文本 blob，跳过 1 个二进制或超大对象。
- `git diff --check`：通过；Windows Gradle Wrapper 的 CRLF 行尾由 Git 检查按预期提示，不是源代码空白错误。

## 真机测试结果

- 无线 ADB：恰有 1 台 ready Pad；未记录或公开序列号、IP、端口。
- Debug APK 安装、启动、Compose 控件可见：通过。
- Accessibility 服务：系统识别并在助手 UI 显示“已启用”；测试后恢复原有辅助功能设置。
- MediaProjection：系统拒绝分支已触发；允许分支完成并在 UI 显示“已采集”；仅选择助手自身作为共享应用。
- 目标游戏窗口：正常、基于真实边界的 resize、恢复原始边界共 3 个私有样本；resize 实际生效，恢复样本与初始样本一致。
- 未对游戏界面执行点击、手势、OCR、模板匹配或副本操作。

## 已知问题

- Pad 的中文输入法会改写 shell 文本注入中的点号，导致 ADB 自动填充包名在 UI 中被格式校验拒绝；真实用户手动输入和单元测试不受影响。
- Pad 上存在同名应用多实例，直接启动时会出现系统实例选择器；测试仅选择助手实例，未选择或操作游戏。

## 未完成内容

- 不包含副本流程、OCR、模板识别、游戏坐标、窗口尺寸预设或任何游戏自动点击。
- 不包含真实目标游戏页面/战斗/结算的自动化实现。

## 后续建议

- 在下一需求中由 ChatGPT 基于私有采样审核窗口适配策略，先定义确认流程再实现页面识别。
- 为本机包名输入增加可访问性语义与手工真机回归用例；不要通过 shell 输入法注入替代用户确认。
- 将 `testDebugUnitTest`、`connectedDebugAndroidTest`、Pad Inspector 测试和安全扫描纳入 CI/发布前检查。

## Review-0004 修订

实现提交 `c3ffb15da44dd8e5df2e6a11ce110cd01358bfb8` 修复本轮全部 BLOCKER、TEST/SECURITY 项，并完成建议的 DailyExecution 幂等测试：

- BLOCKER-01：MediaProjection 改为持续单会话；每帧在关闭 `Image` 前复制 RGBA、stride 与 timestamp，`latestFrame` 可供后续识别层读取。停止或系统回收投屏进入明确 `Stopped` 状态。
- BLOCKER-02：事件包名改为仅诊断用途；目标有效性改为同时要求 active-root 包名匹配及同包 active/focused 窗口。
- BLOCKER-03：窗口监测改为 `NO_BASELINE/MATCHED/CHANGED/UNAVAILABLE` 门禁。窗口变化会使 READY 回到 WINDOW_CHECK、RUNNING 进入 PAUSED；开始前再次同步检查门禁。
- BLOCKER-04：STOPPED 成为稳定终态，忽略延迟 Fail；Reset 后才返回 IDLE。
- TEST/SECURITY-01：Pad Inspector 的全部原始文本、XML、PNG、JSON 统一由私有 writer 创建并强制 `0600`；测试覆盖每种 artifact。
- IMPROVEMENT-01：DailyExecution DAO 通过 `INSERT IGNORE + getOrCreate` 按业务唯一键复用既有记录，防止 SUCCESS 被新 PENDING 覆盖。

## 修订测试结果

- `testDebugUnitTest`：通过；覆盖 STOPPED 延迟失败、窗口门禁、开始条件和非空帧字节复制。
- `connectedDebugAndroidTest`：本轮修订后曾成功执行 2 项 Room 测试；最后一次完整复跑时无线 ADB 无 ready 设备，任务未启动，属于外部连接不可达，未标记为通过。
- `tools/pad-inspector/test_pad_inspector.py`：5 项通过，包含 `0600` 文件权限。
- 公开仓库安全扫描：通过。
- 真机：助手自身的持续投屏会话在界面更新下达到 33 帧；停止后采集和自动化均为 STOPPED，未出现异步失败污染。游戏窗口可见但助手为 active 时显示“未识别”且开始按钮禁用。系统共享选择器曾切换至无关应用，已立即 force-stop 助手并确认捕获服务停止；没有把该内容写入仓库或报告。

## 修订后已知问题

- 无线 ADB 当前可经 mDNS 发现，但连接服务不可达；需要 Pad 恢复 ready 后重跑最终 `connectedDebugAndroidTest`，再做第二轮复审。

## Git commit hash

`c3ffb15da44dd8e5df2e6a11ce110cd01358bfb8`

## 第二轮复审（R2）修订

### 对应 Review 项

- `R2-BLOCKER-01`：修复 GUI 无法完成安全启动握手的问题。
- `R2-TEST-GATE-01`：在最终代码上完成构建、单元测试、连接设备测试、Pad Inspector 测试、安全扫描和真机 GUI 回归。

### 修改文件

- `app/src/main/java/com/lidquan/yishigame/MainViewModel.kt`
- `app/src/main/java/com/lidquan/yishigame/accessibility/GameAccessibilityService.kt`
- `app/src/main/java/com/lidquan/yishigame/automation/AutomationState.kt`
- `app/src/main/java/com/lidquan/yishigame/automation/AutomationStateMachine.kt`
- `app/src/main/java/com/lidquan/yishigame/ui/AssistantApp.kt`
- `app/src/test/java/com/lidquan/yishigame/EnvironmentUiStateTest.kt`
- `app/src/test/java/com/lidquan/yishigame/automation/AutomationStateMachineTest.kt`

### 新增文件

- 无。未新增 REQ，未开始 OCR、副本或战斗功能。

### 核心实现说明

- 将目标“可见”和“已激活”拆分。助手获得焦点时仍可完成环境检查并点击“准备启动”，随后进入 `WAIT_TARGET_ACTIVE`。
- 只有目标窗口重新获得 active/focused、采集会话仍为 Active、窗口门禁仍为 MATCHED 时，才进入 `RUNNING_PLACEHOLDER`。
- 运行中失焦进入 `PAUSED`；目标重新获得焦点不会自动恢复，必须先由用户点击“显式恢复并重新握手”。
- 窗口变化、窗口不可用、采集失效或辅助服务失效标记为 `ENVIRONMENT_CHECK`，禁止走普通显式恢复。
- 目标激活等待超过 30 秒时安全暂停。
- 针对 Cocos/SurfaceView 根节点包名为空的情况，以 Accessibility 窗口事件的 windowId 建立本地窗口包名映射；active/focused 仍取实时窗口属性，不把旧事件当作前台依据。
- 辅助功能可用性只认服务真实连接状态，不再把系统组件列表中的残留条目误判为已启用。

### 关键技术决策

- 采用“显式恢复 + 二阶段安全握手”，不因目标游戏重新获得焦点自动恢复。
- 窗口和采集故障必须重新环境检查；只有单纯失焦或等待超时允许用户显式发起重新握手。
- M0 仍只进入运行占位态，不发送任何游戏点击、手势或返回操作。

### 构建与自动测试结果

- `testDebugUnitTest assembleDebug`：通过，`BUILD SUCCESSFUL`。
- `connectedDebugAndroidTest`：通过，2 项测试、0 失败、0 跳过。
- Pad Inspector：5 项通过。
- 公开仓库安全扫描 self-test 与 `--history`：通过；88 个当前/索引文本候选、123 个历史文本对象，跳过 2 个二进制或超大对象。
- `git diff --check`：通过。
- Android SDK XML 版本提示仍存在，但不阻塞构建。

### 真机测试结果

- 无线 ADB 目标 Pad 在线；仅安装 1 个 user 0 助手包。
- 辅助服务真实绑定，目标游戏窗口在助手获得焦点时显示为“可见”，窗口基线可建立并匹配。
- 屏幕采集会话持续产出非空帧；测试只共享助手自身，不采集游戏画面。
- GUI 实测通过：环境检查 → `READY` → 准备启动 → `WAIT_TARGET_ACTIVE` → 游戏获得焦点 → `RUNNING_PLACEHOLDER` → 失焦 → `PAUSED`。
- `PAUSED` 后仅把游戏切回前台不会自动恢复；点击“显式恢复并重新握手”后再次完成第二阶段才进入运行占位态。
- 全流程未点击游戏内容区，未执行 OCR、模板匹配、副本或战斗操作。
- 连接设备测试会卸载被测 APK；测试后已只重装同一 APK，恢复辅助服务，并重新保存、核验本机目标配置。实际包名和设备地址未写入仓库。

### 已知问题与未完成内容

- 该 Pad 的系统应用选择器仍会展示其他用户空间的同名入口；user 0 实际只安装一个助手包。
- 覆盖安装或连接设备测试后，ZUI 会关闭辅助功能总开关；测试流程需重新确认服务真实绑定。
- 屏幕采集授权按 Android 机制在应用重装或会话结束后需要重新授予。
- R2-IMPROVEMENT-01 的高分辨率帧缓冲复用留待进入视觉识别前处理，本轮未扩展范围。

### 后续建议

- 先由 ChatGPT 完成第三轮复审；通过前不要合并 main，也不要开始 OCR、副本或战斗功能。
- 后续真机回归避免在最终安装之后再运行 `connectedDebugAndroidTest`，因为该任务会卸载测试 APK。

### R2 实现 commit hash

`a8c57d61566380a0e5fa0153c351db28d39dfa52`
