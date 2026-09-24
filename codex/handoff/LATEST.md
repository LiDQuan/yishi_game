# 最新交接：REQ-0006（未完成）

## 2026-09-24 Precheck 续测更新

- 最新实现提交：`8de00aa10698edb4c492a931aaf3a96eb1ad642f`；仍在 `feat/req-0006-single-dungeon-loop`，未合入 main。
- 代码变化：失败状态下允许显式触发 Precheck 并输出原因；拒绝过期或 viewport 版本不匹配的稳定页面；扩充失败分支 JVM 测试。
- `git diff --stat 54ca9aeb..8de00aa`：3 个文件，57 行新增、5 行删除；完整代码差异另见 `LATEST.patch`。
- 测试：50 项 JVM 通过；Debug 构建通过；设备测试 3 通过、1 私有样本缺失而跳过；公开安全扫描和 diff 检查通过。
- 真机：游戏左、助手右、HOME 可见；Precheck 失败码 `PRECHECK_ACCESSIBILITY_UNAVAILABLE`，没有真实游戏业务点击。设备测试重装助手后系统授权失效，辅助功能尚未由用户重新打开。**没有 READY，也没有单副本 SUCCESS。**
- 复审重点：先确认失败态按钮可达与旧页面拒绝逻辑；待授权恢复后再审 READY、各真机失败场景及区域切换后置条件。下文是此前提交的交接内容。

- 当前任务编号：`REQ-0006`
- 当前版本：`0.2.0-m1`（debug）
- 分支：`feat/req-0006-single-dungeon-loop`
- 完整实现 commit hash：`c3820ff687b25c77ee265e898c98c79203b02366`
- 本次修改摘要：增加受 Guard 控制的真实 tap 执行器、单副本运行逻辑、区域/地下城/详情/战斗视觉信号、免费次数与进度解析、DailyExecution、私有结构化日志及测试。**未跑通完整副本。**

## git diff --stat（main 基线至实现提交）

```text
 .../java/com/lidquan/yishigame/MainViewModel.kt    | 255 ++++++++++++++++++++-
 .../com/lidquan/yishigame/action/ActionExecutor.kt |  40 ++++
 .../com/lidquan/yishigame/action/ActionGuard.kt    |  23 +-
 .../com/lidquan/yishigame/automation/DungeonRun.kt |  43 ++++
 .../main/java/com/lidquan/yishigame/data/Daos.kt   |   1 +
 .../com/lidquan/yishigame/diagnostics/RunLogger.kt |  38 +++
 .../java/com/lidquan/yishigame/ui/AssistantApp.kt  |   9 +-
 .../yishigame/viewport/ContentViewportDetector.kt  |  38 +++
 .../yishigame/vision/ConfiguredVisionEngine.kt     | 137 ++++++++++-
 .../com/lidquan/yishigame/vision/VisionModels.kt   |   7 +
 .../com/lidquan/yishigame/vision/VisionWorker.kt   |  12 +
 .../com/lidquan/yishigame/vision/ocr/OcrEngine.kt  |  15 +-
 .../lidquan/yishigame/action/ActionExecutorTest.kt |  42 ++++
 .../lidquan/yishigame/action/ActionGuardTest.kt    |   2 +-
 .../yishigame/automation/DungeonRunReducerTest.kt  |  36 +++
 .../viewport/ContentViewportDetectorTest.kt        |  32 +++
 .../yishigame/vision/VisionConfigurationTest.kt    |   7 +-
 .../yishigame/vision/ocr/CounterParserTest.kt      |  28 +++
18 files changed, 739 insertions(+), 26 deletions(-)
```

完整代码差异见 `codex/handoff/LATEST.patch`，其基线为 `450875226388ca4eefff4e4ada772a8c41a814d4`，终点为上述实现提交。

## 关键代码改动与测试结果

- `MainViewModel` 驱动有限次数/时长的单次运行；失败与成功写 DailyExecution。
- `ActionExecutor` 执行 Guard 允许的辅助功能 tap 并验证后置页面；购买动作仍被策略禁止。
- `ConfiguredVisionEngine` 单次 OCR 复用、页面与区域信号、详情页免费次数、战斗进度以及首个列表免费角标。
- `RunLogger` 将业务状态及动作记录到 App 私有目录，公开仓库只提交脱敏摘要。
- 构建与 40 个 JVM 测试通过；设备测试 3 通过、1 因缺少私有样本跳过；公开仓库安全扫描、`git diff --check` 通过。

## 错误与未解决问题

- 已观察的真实区域选择动作之后，页面后置条件超时：`ACTION_SELECT_AREA_BLOCKED_OR_UNVERIFIED`。识别逻辑已调整，仍待联网真机回归。
- 游戏出现“网络错误 / 已与服务器断开连接”，本轮无法继续完整业务测试。
- 自动战斗 OFF / MISSING 无可靠识别，当前 UNKNOWN 时安全停止；首个副本角标 ROI、实际副本 ID 与同日去重也未真机验收。
- 没有任何 `SUCCESS` 会话，不满足 REQ-0006 验收。原始早期会话日志因设备测试重装助手而未保留；报告没有伪造 sessionId、免费次数或进度。

## 希望 ChatGPT 重点审查

1. 是否接受当前“先安全停止、不误点”的未完成分支交接，还是要求继续真机采样后再 Review。
2. 区域切换后置条件、T06 角标 ROI、T07 免费次数和 Guard 目标矩形是否足够精确。
3. 自动战斗识别、真实副本 ID 去重和日志完整性是否满足第 21/22 节；当前结论是**尚未满足**。
