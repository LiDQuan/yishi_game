# ChatGPT 第三轮复审交接

## 当前任务编号

REQ-0005

## 当前版本

第二轮 REVIEW-0005 BLOCKER/REQUIRED 修复版；等待第三轮 Review。

## 本次修改摘要

ActionPolicy 已中心化；FreeAttemptState 仅消费显式 FREE_ATTEMPT semantic ROI；页面 anchor 与动作目标分流，动作目标使用真实 OCR boundingBox 并检查 contentViewport；Stop 后同一 VisionWorker 可继续处理新 capture；Template negative 统一归 PageDefinition。没有新增需求、Action Executor 或游戏输入。

## 完整 commit hash

`fd0f318daa8135ea33393dba36d45ec68b3d64c6`

该 hash 为本轮实现提交；本交接与 patch 由后续元数据提交归档。

## git diff --stat

```text
14 files changed, 190 insertions(+), 62 deletions(-)
```

比较范围：`6a5535316855a36889940bb202edd80a65f8dcd2..fd0f318daa8135ea33393dba36d45ec68b3d64c6`。完整差异见 `codex/handoff/LATEST.patch`。

## 关键代码改动说明

- ActionIntent 不再接受 risk、target 或 policy 字段；ActionContext 不再接受 allowedPages 或 expectedPagesAfter。
- Registry 固定敏感与永久禁止策略，未知/缺失 policy 默认 POLICY_MISSING。
- 普通 OCR、FREE_ATTEMPT OCR 和动作目标证据完全分流。
- `dungeon.switch_region` 来自真实按钮文字 line boundingBox；UI显示 targetRect/tapPoint，但不会执行。
- Guard 新增 TARGET_OUTSIDE_VIEWPORT。
- Stop 使用 invalidate；onCleared 才 cancel worker。
- 删除 TemplateDefinition、manifest、sampler 中重复的 negative 字段。

## 测试结果

- JVM：31 项通过，0 失败，0 跳过。
- Android connected tests：通过。
- 最终 APK 私有三页 fixture：1 项通过，1.571 秒。
- Pad Inspector / Vision Sampler：6 项通过。
- ActionPolicy 绕过、semantic free isolation、OCR实际矩形、视口包含、worker恢复测试均通过。
- 安全扫描（含历史）与 `git diff --check` 通过。
- 全程 0 游戏 gesture。

## 错误信息

- Gradle 重装仍会清除应用私有 fixture；测试按既定流程在最终 APK 上重新注入本机私有样本后直接运行并通过。
- Android SDK XML 版本提示不阻塞。

## 尚未解决的问题

- OCR动作目标是按钮文字矩形，不是完整按钮外框；未来真实 Executor 前仍需更强的已审核视觉目标。
- 免费次数真实 ROI 尚未确认，默认 UNKNOWN。
- 非等值 contentViewport 校准入口按 R2 结论留待后续。

## 希望 ChatGPT 重点审查的内容

- 调用方是否已无法降低风险、扩大 allowedPages 或伪造 expectedPagesAfter。
- FREE_ATTEMPT 是否与普通/其他 semantic OCR 完全隔离。
- ActionTargetEvidence 与页面 evidence 的边界、实际 rect 和视口包含检查。
- Stop 后常驻 worker 的恢复行为。
- Template negative 是否已成为 PageDefinition 单一事实来源。
