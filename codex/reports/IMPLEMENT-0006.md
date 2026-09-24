# IMPLEMENT-0006 — 未完成的真机开发交接

## 2026-09-24 启动前 Precheck 续测（仍未完成）

- 本轮实现提交：`8de00aa10698edb4c492a931aaf3a96eb1ad642f`；继续使用原开发分支，未合入 main。
- 修复了两处启动门槛问题：环境不合格时现在仍可按测试按钮并获得明确 `PRECHECK_FAILED`；稳定页面必须与当前 viewport 版本一致且采样时间不超过 2 秒，避免旧 HOME 结果被误用。
- 为辅助功能、截图权限、游戏窗口、viewport、稳定页面及 WindowGate 缺失补了 JVM 失败用例。`./gradlew test`：50 项通过；`assembleDebug`：通过；`connectedDebugAndroidTest`：3 项通过、1 项因缺少私有样本跳过。`git diff --check` 与公开仓库安全扫描通过。
- 真机：有线 ADB 已连接；游戏在左、助手在右，游戏停在 HOME。点击助手测试按钮后确实显示 `PRECHECK_FAILED / PRECHECK_ACCESSIBILITY_UNAVAILABLE`，没有业务点击。设备测试重新安装了助手，导致 Android 辅助功能和屏幕采集授权失效；目前辅助功能系统开关仍关闭，尚未得到用户重新授权。因此 **未观察到 `REQ-0006_PRECHECK READY`**，其他失败场景只有 JVM 验证，不能宣称真机验证。
- 当前实际业务位置仍是 HOME，最后动作是助手内的 Precheck 按钮，错误码 `PRECHECK_ACCESSIBILITY_UNAVAILABLE`；本轮真实游戏业务 tap 为 0，**无真实 SUCCESS**。授权恢复后应先完成剩余 Precheck 真机矩阵，再开始受控副本链。下文记载的是此前会话，不是本轮成功证据。

- 对应需求：`REQ-0006`
- 分支：`feat/req-0006-single-dungeon-loop`
- 实现提交：`c3820ff687b25c77ee265e898c98c79203b02366`
- 结论：**未达到 REQ-0006 验收标准，不应合入 main 或标记 ACCEPTED。**

## 本次实现目标与实际进展

目标是当前角色单副本完整闭环。已接入最小 Action Executor、页面识别、免费次数解析、区域切换、战斗进度观察、DailyExecution 和本机私有结构化日志。真机上曾通过受控 Action Guard 完成 T01→区域地图→区域选择→切换至目标区域，后续页面识别超时；随后游戏出现“网络错误 / 已与服务器断开连接”。本次恢复期间仍见该错误，无法继续真实副本。没有真实通关，也没有 DailyExecution SUCCESS。

## 修改文件

`MainViewModel.kt`、`ActionGuard.kt`、`Daos.kt`、`AssistantApp.kt`、`ConfiguredVisionEngine.kt`、`VisionModels.kt`、`VisionWorker.kt`、`OcrEngine.kt`、`ActionGuardTest.kt`、`VisionConfigurationTest.kt`。

## 新增文件

`ActionExecutor.kt`、`DungeonRun.kt`、`RunLogger.kt`、`ContentViewportDetector.kt` 及对应的四个 JVM 测试文件。

## 核心实现与技术决策

- 真实游戏 tap 只经 `ActionExecutor`：先由 Action Guard 核验稳定页面、目标窗口、Viewport、识别目标及敏感操作条件，再调用辅助功能 tap，随后等待页面后置条件。没有通过 ADB 向游戏注入 tap。
- 详情页解析 `免费(current/total)`；仅 `current > 0` 可进入。无法确认则失败停止。列表候选改为观察首个副本右上角数字角标，不再以 `Lv` 文字冒充免费标记。
- 进度采用 OCR 的 `current/total`，不写死 5/5；仅观察到完成进度且返回 T01 才写 SUCCESS。若进入战斗后未完成即返回，写 `DUNGEON_INCOMPLETE`。
- 游戏异常对话框不自动点“确定”；返回 `GAME_DIALOG_REQUIRES_USER`。自动战斗未亮与图标缺失尚未可靠区分，统一视为 UNKNOWN 并以 `AUTO_BATTLE_UNCONFIRMED` 停止，避免误触。
- 运行日志保存在 App 私有目录；公开仓库只放脱敏摘要。当前日志结构已含业务状态、动作目标、Guard 决策、tap 结果和耗时，但尚未经成功会话验证。

## 构建与自动测试

- `assembleDebug`：通过。
- `testDebugUnitTest`：40/40 通过。
- `connectedDebugAndroidTest`：4 项执行，3 通过、1 项因缺少私有 fixture 跳过；Gradle 整体通过。
- `git diff --check`：通过。
- `python3 tools/security/check_public_repo.py --history`：通过；未提交原始截图、设备地址、账号或本机原始日志。

## 真机测试结果

- 有线 ADB 识别到目标 Pad；未把设备标识写入仓库。
- 中断前的受控运行完成了主界面副本入口、打开区域选择和真实区域切换；切换后 `ACTION_SELECT_AREA_BLOCKED_OR_UNVERIFIED`，没有到达地下城列表。
- 恢复后游戏显示“网络错误 / 已与服务器断开连接”；未在断线状态下注入游戏点击。测试 APK 的设备测试会卸载/重装助手，导致此前 App 私有运行日志不再保留。真实会话摘要见 `codex/reports/runs/REQ-0006-run-summary.md`。

## 已知问题、未完成内容、后续建议

1. **阻塞验收：** 未验证 T06 角标、T07 免费次数、真实前往、战斗自动开关、进度达到 total/total、自动回 T01、同日再次运行跳过。服务器恢复后必须重新跑完整会话，并保留私有 JSONL 及脱敏摘要。
2. 首个角标的 OCR ROI 与点击矩形只基于真实截图定位，尚未在最新构建的实时画面验证。无正向识别时 Guard 会拒绝点击。
3. 自动战斗 OFF / MISSING 检测仍缺可靠真机样本；当前安全停止而非盲点。补齐本机私有样本并验证后才能开启该动作。
4. `currentDungeon` 尚未从详情标题可靠提取；DailyExecution 暂用单副本占位键，不能证明按实际副本 ID 去重。需在真实闭环前修正。
5. 游戏正常连通后，先验证识别与动作后置条件，再执行一次完整成功和一次同日跳过；未成功前不要扩展多副本或新需求。

## Git commit hash

实现提交：`c3820ff687b25c77ee265e898c98c79203b02366`。本报告与 handoff 由后续文档提交承载；最终分支 HEAD 见交接消息。
