# Action Guard 设计

对应需求：REQ-0005

## 1. 定位

Action Guard 是未来所有游戏输入的唯一安全入口。

本阶段只做：

```text
plan / validate / dry-run
```

不执行 Accessibility gesture。

未来即使 Task Engine、Battle Engine、角色切换模块都想点击，也不能直接调用：

```text
AccessibilityController.tap()
```

必须：

```text
Business Intent
→ Action Guard
→ Decision
→ Action Executor
```

## 2. ActionIntent

动作是语义，不是坐标。

例如：

```text
OPEN_SETTINGS
RETURN_CHARACTER_SELECT
SELECT_ROLE
OPEN_DUNGEON
START_DUNGEON_FREE
CLOSE_SAFE_DIALOG
```

而不是：

```text
TAP(1000, 800)
```

ActionIntent 可带参数：

```kotlin
data class ActionIntent(
    val type: ActionType,
    val targetId: String?,
    val riskLevel: RiskLevel,
)
```

## 3. ActionContext

Guard 至少检查：

```text
automation state
target active
window gate
capture state
stable page
viewport version
vision facts
daily execution facts
risk rules
```

## 4. GuardDecision

```kotlin
sealed interface GuardDecision {
    data class AllowDryRun(
        val plan: ActionPlan,
        val evidences: List<GuardEvidence>,
    ) : GuardDecision

    data class Deny(
        val reason: GuardDenyReason,
        val evidences: List<GuardEvidence>,
    ) : GuardDecision
}
```

M1 不允许返回 Execute。

## 5. 全局硬门禁

任何动作前必须满足：

- 自动化状态允许。
- Accessibility service connected。
- capture Active。
- targetVisible。
- targetActive。
- WindowGate.MATCHED。
- viewport valid。
- StablePage 非 stale。

任何一项失败：

```text
DENY
```

## 6. 页面门禁

每个动作定义允许来源页面。

例：

```text
OPEN_SETTINGS
allowed pages:
MAIN
```

即使识别到“设置”图标，但 stable page 不是 MAIN，也拒绝。

## 7. 风险门禁

### SAFE

仍需全局门禁和页面门禁。

### NORMAL

需要：

- stable page。
- target anchor。
- postcondition definition。

### SENSITIVE

额外需要业务证据。

例如：

```text
START_DUNGEON_FREE
```

必须同时：

- page = DUNGEON_DETAIL。
- DailyExecution 今日未 SUCCESS。
- FreeAttemptState = AVAILABLE。
- purchase/paid negative signal = absent。
- 按钮 anchor 高置信度。
- target active。
- window matched。

任何证据 UNKNOWN：

```text
DENY
```

### FORBIDDEN_AUTO

永远：

```text
DENY
```

即使页面和模板都匹配。

## 8. ActionPlan

M1 可以生成但不执行：

```kotlin
data class ActionPlan(
    val intent: ActionIntent,
    val targetRect: Rect,
    val tapPoint: Point,
    val expectedPageBefore: String,
    val expectedPageAfter: Set<String>,
    val timeoutMs: Long,
)
```

tapPoint 必须从 targetRect / normalized viewport 推导。

## 9. Postcondition

现在只定义，不执行动作。

未来 Executor：

```text
Guard Allow
→ tap
→ wait
→ PageDetector
→ postcondition
```

如果没有进入 expected page：

- 不连点。
- 有限 retry。
- 进入 Recovery。

## 10. Dry-Run UI

Vision Debug Screen 可以选择某 ActionIntent。

显示：

```text
Intent: START_DUNGEON_FREE
Decision: DENY
Reason: FREE_STATE_UNKNOWN
StablePage: DUNGEON_DETAIL 0.96
TargetActive: yes
WindowGate: MATCHED
```

或：

```text
Decision: ALLOW_DRY_RUN
Would tap: normalized (0.84, 0.91)
Expected next: DUNGEON_LOADING / BATTLE_LOADING
```

但不调用 gesture。

## 11. 审计

每个 Decision 记录：

- intent。
- riskLevel。
- decision。
- reason。
- pageId。
- pageConfidence。
- viewportVersion。
- timestamp。

不记录图片 bytes。

## 12. 防旁路规则

后续 Code Review 必须检查：

```text
AccessibilityController.tap/swipe/back
```

不得在：

- Task Engine。
- Battle Engine。
- UI。
- Scheduler。

直接出现。

只有 Action Executor 可以持有 controller。

M1 可以先不创建真实 Executor，或创建始终拒绝执行的 DryRunExecutor。
