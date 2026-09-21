# IMPLEMENT-0005：第二轮 Review 修复

## 对应需求编号

REQ-0005

## 本次实现目标

仅修复 `REVIEW-0005` 第二轮结论列出的 4 个 BLOCKER 和 Template negative REQUIRED 项。不新增 REQ-0006，不实现真实 Action Executor，不执行游戏输入，不开始副本、角色切换或战斗。

## 修改文件

- `action/ActionGuard.kt`：集中 ActionPolicy，Context 只保留事实，增加动作目标视口包含校验。
- `vision/ConfiguredVisionEngine.kt`：页面 OCR、语义 OCR、动作目标证据分流。
- `vision/VisionModels.kt`、`VisionWorker.kt`：传递独立 ActionTargetEvidence。
- `MainViewModel.kt`：Stop 仅清视觉状态，不终止常驻 worker；Dry-Run 消费中心政策与独立动作目标。
- `ui/AssistantApp.kt`：显示真实 targetRect 与 tapPoint。
- Template manifest、sampler、matcher：删除重复的 template negative 字段。
- JVM 与 Android 私有 fixture 测试同步更新。

## 新增文件

- `app/src/test/java/com/lidquan/yishigame/vision/ConfiguredVisionEngineTest.kt`
- `app/src/test/java/com/lidquan/yishigame/vision/VisionWorkerTest.kt`

## 核心实现说明

- `ActionIntent` 现在只有 ActionType。riskLevel、allowedPages、requiredTargetId、expectedPagesAfter 全部由 `ActionPolicyRegistry` 唯一决定。
- `START_DUNGEON_FREE` 固定为 SENSITIVE；`PURCHASE` 固定为 FORBIDDEN_AUTO。调用方已无字段可降低风险、扩大页面或伪造后置页面。
- FreeAttemptState 只读取类型为 FREE_ATTEMPT 的 semantic ROI；普通页面 OCR 和其他 semantic 类型不会污染状态，无配置强制 UNKNOWN。
- 页面识别 evidence 与动作目标 evidence 使用不同 ID/通道。动作目标 rect 来自 ML Kit 实际 line boundingBox，不再使用搜索 ROI。
- Guard 必须从中心政策指定的 target ID 取证，并确认整个 rect 位于 contentViewport 内，否则分别返回 TARGET_NOT_CONFIRMED / TARGET_OUTSIDE_VIEWPORT。
- Stop 仍停止 capture 并清空视觉结果，但不 cancel VisionWorker；同一 worker 在新帧到达后可重新形成 detection/stablePage。仅 ViewModel onCleared 真正停止 worker。
- TemplateDefinition 不再含 negative；正负规则统一由 PageDefinition 管理。

## 关键技术决策

- 沿用现有类型和数据流完成修复，未增加依赖或动作执行层。
- 私有截图目视确认“切换区域”为真实按钮；Dry-Run 只展示其文字 boundingBox 中心，不执行该点。
- R2-REQUIRED-06 被 Review 明确列为后续校准入口且未列入本轮五项修复结论，因此本轮不扩展校准 UI，也不猜 inset。

## 构建结果

- `assembleDebug`：通过。
- Android test 编译：通过。
- Android SDK XML 版本提示仍不阻塞。

## 自动化测试结果

- JVM：31 项通过，0 失败，0 跳过。
- ActionPolicy bypass、免费语义隔离、真实 OCR rect、target containment、worker invalidate→重新识别均有自动测试。
- Pad Inspector / Vision Sampler：6 项通过。
- `git diff --check`：通过。

## ADB / 真机测试结果

- 无线 ADB 目标 Pad在线。
- `connectedDebugAndroidTest`：通过。
- 最终 APK 上单独注入本机私有 fixture 后，真实三页测试 1 项通过（1.571 秒）。
- SETTINGS、CHARACTER_SELECT、DUNGEON_LIST 继续识别成功。
- DUNGEON_LIST 的独立动作目标来自实际 OCR boundingBox；ALLOW_DRY_RUN 以及失焦、窗口变化、缺目标 DENY 均通过。
- Stop→invalidate→新帧重新形成 StablePage 的同 worker 生命周期测试通过。
- 全程 0 游戏 gesture。

## 安全扫描

- 扫描器自测通过。
- 当前/索引与完整 Git 历史扫描通过；私有截图、元数据、设备地址和凭据未提交。
- 本轮未增加或调用任何手势执行路径。

## 已知问题

- 当前动作目标使用按钮文字 boundingBox，而不是完整按钮外框；满足 Dry-Run 证据闭环，但未来真实 Executor 前应优先使用已审核的按钮模板或可访问节点边界。
- 真实模板仍为本机私有 PENDING 资产。
- contentViewport 的非等值校准入口按 R2 结论留待后续，不猜 inset。

## 未完成内容

- 未实现 Action Executor、真实点击、滑动或返回。
- 未开始副本、角色切换或战斗。
- 未配置未经真实样本确认的免费次数 ROI。

## 后续建议

- 等待 ChatGPT 第三轮 Review，重点复审中心政策不可绕过性、semantic OCR 隔离、动作目标证据和 Stop 生命周期。

## Git commit hash

`fd0f318daa8135ea33393dba36d45ec68b3d64c6`
