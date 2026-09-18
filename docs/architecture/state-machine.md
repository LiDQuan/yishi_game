# 自动化状态机

对应需求：REQ-0003

## 1. 原则

- 状态机是自动化唯一事实来源。
- UNKNOWN 不等于失败，但 UNKNOWN 下禁止危险点击。
- 每次动作必须有前置页面和后置条件。
- STOP 优先级最高。
- ERROR 默认不自动恢复到点击态，除非该错误被明确标记为可恢复。

## 2. 顶层状态

```text
IDLE
  ↓
PRECHECK
  ├─ PERMISSION_REQUIRED
  ├─ ENV_ERROR
  └─ WINDOW_CHECK
          ↓
     WINDOW_NORMALIZE
          ↓
     PAGE_IDENTIFY
          ↓
        READY
          ↓
       RUNNING
          ├─ ROLE_SWITCH
          ├─ DUNGEON_FLOW
          ├─ BATTLE
          ├─ DAILY_TASK
          └─ REPORT
          ↓
       COMPLETED
```

任意运行状态：

```text
→ PAUSED
→ ERROR
→ STOPPING
→ STOPPED
```

## 3. PRECHECK

检查：

- Accessibility。
- MediaProjection。
- 数据库。
- 配置。
- 目标 App。
- 当前日期滚动。
- 是否存在未恢复 Session。

失败：

- 权限缺失 → PERMISSION_REQUIRED。
- 配置损坏 → ERROR。
- 目标窗口不可见 → WAIT_TARGET_APP / ERROR（实现时细化）。

## 4. WINDOW_CHECK

读取：

- 当前目标窗口 bounds。
- viewport。
- orientation。

与标准配置比较。

```text
MATCH → PAGE_IDENTIFY
CHANGED → WINDOW_NORMALIZE
UNAVAILABLE → WAIT_USER
```

运行期间由 Window Watcher 触发 WINDOW_BOUNDS_CHANGED 时：

1. 立即阻止新 Action。
2. PAUSE。
3. WINDOW_CHECK。
4. 重新识别当前页面。
5. 仅在安全恢复后继续。

## 5. PAGE_IDENTIFY

页面识别结果：

- MATCH(pageId, confidence)
- UNKNOWN
- AMBIGUOUS

UNKNOWN/AMBIGUOUS：

- 短等待重试。
- 有限次数重新截屏。
- 尝试安全返回仅限明确允许的恢复策略。
- 仍失败 → ERROR / WAIT_USER。

## 6. Scheduler WorkItem

统一工作项：

```text
SWITCH_ROLE
RUN_DUNGEON
RUN_DAILY_TASK
GENERATE_REPORT
FINISH
```

Scheduler 只根据数据库和计划决定下一项，不直接操作 UI。

## 7. ROLE_SWITCH

```text
VERIFY_CURRENT_ROLE
→ OPEN_SETTINGS
→ RETURN_CHARACTER_SELECT
→ DETECT_CHARACTER_LIST
→ SELECT_TARGET_ROLE
→ ENTER_GAME
→ WAIT_LOAD
→ VERIFY_TARGET_ROLE
→ SUCCESS
```

任何一步页面不匹配：

```text
RETRY_LIMITED
→ RECOVERY
→ ERROR
```

SELECT 后必须二次确认角色身份。

## 8. DUNGEON_FLOW

```text
NAVIGATE_DUNGEON_LIST
→ SELECT_DUNGEON
→ VERIFY_DETAIL
→ CHECK_DAILY_DB
→ DETECT_FREE_STATE
→ CONSUMPTION_GUARD
→ START
→ WAIT_LOAD
→ BATTLE
→ RESULT
→ REWARD
→ MARK_DAILY_SUCCESS
→ RETURN
```

关键分支：

### 已完成

DailyExecution 已 SUCCESS：

```text
SKIP_ALREADY_DONE
```

### 免费状态无法确认

```text
SKIP_FREE_NOT_CONFIRMED
```

不得点击。

### 发现消费风险

```text
ERROR / SKIP_CONSUMPTION_RISK
```

不得自动确认。

## 9. BATTLE

进入：

```text
DETECT_BATTLE_MODE
```

### NATIVE_AUTO

```text
VERIFY_AUTO_ACTIVE
→ WATCH_RESULT
```

定期只做：

- 结果识别。
- 掉线/异常识别。
- 超时保护。

### SCRIPT_CAST

循环：

```text
CAPTURE
→ BUILD_BATTLE_SNAPSHOT
→ EVALUATE_RULES
→ SELECT_ACTION
→ ACTION_GUARD
→ CAST
→ OBSERVE
```

并行监视胜负结果。

关键规则：

- 每轮只执行一个经过优先级选择的动作。
- 关键技能有最小 confidence。
- 无可执行动作时等待下一轮，不乱点。

## 10. DAILY_TASK

任务执行器读取 TaskDefinition：

```text
PRECONDITION
→ STEP_1
→ STEP_2
→ ...
→ SUCCESS_CONDITION
→ MARK_SUCCESS
```

每个 step：

- maxRetries
- timeout
- riskLevel
- expectedPage
- postCondition

## 11. ERROR 与恢复

ErrorEvent 保存：

- state
- workItem
- page
- confidence
- lastAction
- retryCount
- errorCode

错误分级：

### Recoverable

例：

- 短暂页面加载。
- 可明确关闭的普通弹窗。
- 窗口变化。

允许有限自动恢复。

### NeedsUser

例：

- 未知消费弹窗。
- 长时间 UNKNOWN。
- 权限被撤销。
- 新版本 UI 无模板。

停止点击并等待用户。

### Fatal

例：

- 数据库严重错误。
- 核心配置 schema 无法加载。

结束 Session。

## 12. 断点续跑

持久化：

- sessionId。
- 当前 WorkItem。
- 已成功 DailyExecution。
- 最后安全状态。

重启后不能直接恢复到“点击一半”的中间动作。

恢复原则：

```text
加载数据库
→ 重新 PRECHECK
→ WINDOW_CHECK
→ PAGE_IDENTIFY
→ 根据 DailyExecution 重新计算 Scheduler
```

这比保存“下一次点击坐标”更安全。

## 13. 每日边界

当本地日期变化：

- 新 DailyExecution namespace。
- 不删除旧记录。
- 若运行中跨天，当前动作完成后重新生成计划。
- 具体游戏每日重置时间如不是 00:00，后续增加 GameDayProvider，不要硬编码在业务表。
