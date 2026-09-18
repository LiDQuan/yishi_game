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

## Git commit hash

`799c044380766dbb13a234665d919fc842136bed`
