# 数据模型草案

对应需求：REQ-0003

本文件定义语义，不强制最终 Room 类名完全一致。Codex可根据 Room/Kotlin惯例调整，但不得改变幂等和审计语义。

## 1. Role

```text
id: String PK
displayName: String
classId: String?
level: Int?
enabled: Boolean
sortOrder: Int
nativeAutoPreference: AUTO_DETECT | FORCE_NATIVE | FORCE_SCRIPT
battleStrategyId: String?
createdAt
updatedAt
```

displayName 如果用户认为敏感，应只保存在本地数据库，不进入公开示例。

## 2. Dungeon

```text
id: String PK
name: String
enabled: Boolean
version: String
riskLevel
templateSetId: String?
createdAt
updatedAt
```

## 3. RoleDungeonPlan

```text
roleId
dungeonId
enabled
sortOrder
useDailyFreeOnly: Boolean = true
```

联合主键：

```text
roleId + dungeonId
```

## 4. TaskDefinition

```text
id
name
version
enabled
scope: ACCOUNT | ROLE
definitionJson
riskLevel
createdAt
updatedAt
```

真实步骤由 schema 校验后的 JSON/结构表存储。

## 5. RoleTaskPlan

```text
roleId
taskId
enabled
sortOrder
```

## 6. DailyExecution

核心幂等表。

```text
id
gameDayKey
roleId
actionType: DUNGEON | TASK
targetId
status: PENDING | RUNNING | SUCCESS | SKIPPED | FAILED
attemptCount
startedAt
finishedAt
resultCode
errorCode?
metadataJson?
```

唯一约束：

```text
gameDayKey + roleId + actionType + targetId
```

对于账号级任务，roleId 使用明确的 ACCOUNT_SCOPE 常量或 nullable + 专门约束，不能随意填字符串导致重复。

## 7. AutomationSession

```text
id
gameDayKey
startedAt
endedAt
status
currentState
currentRoleId?
currentWorkType?
currentTargetId?
lastSafeCheckpoint
stopReason?
```

用于审计，不用于恢复到危险中间点击。

## 8. ErrorEvent

```text
id (例如 ERR-YYYYMMDD-HHMMSS-random)
sessionId
timestamp
state
roleId?
workType?
targetId?
pageId?
pageConfidence?
lastAction?
retryCount
errorCode
message
localScreenshotRef?
contextJson?
resolvedAt?
resolution?
```

localScreenshotRef 默认指向应用私有目录，不上传公开仓库。

## 9. VisionTemplate

可存在配置文件，不一定必须 Room。

语义：

```text
id
templateVersion
gameVersion?
pageId?
roi
threshold
scalePolicy
assetRef
positive/negative
```

模板资源如含用户个人画面，必须脱敏后才能进入公开仓库。

## 10. BattleStrategy

```text
id
name
classId
dungeonId?
bossId?
version
schemaVersion
rulesJson
enabled
sourceRefs
```

规则中技能使用语义 tag 优先于固定槽位，例如：

```text
interrupt
heal
defensive
burst
```

角色可映射 tag → 实际技能槽位。

## 11. SkillDefinition

公开数据文件为主。

```text
id
classId
name
type
cooldown?
resource?
effects[]
tags[]
gameVersion
sources[]
confidence
reviewStatus
```

reviewStatus：

- AUTO_EXTRACTED
- CONFLICT
- HUMAN_REVIEWED

## 12. BossDefinition

```text
id
name
dungeonId
gameVersion
mechanics[]
visualSignals[]
sources[]
reviewStatus
```

## 13. AppSettings

仅保存非敏感运行配置：

- 标准 viewport profile id。
- 默认 OCR threshold。
- 默认 template threshold。
- 自动化模式。
- 日志级别。
- 保留天数。

Token、ADB endpoint、密码不得存这里。

## 14. SyncState

后续远程配置用：

```text
resourceType
resourceId
localVersion
remoteVersion
lastCheckedAt
lastAppliedAt
status
```

## 15. GameDayProvider

不要把“每天”永远等同于手机自然日。

接口：

```text
getGameDayKey(now): String
```

MVP 可先使用本地自然日，若确认游戏每日重置时间不同，再通过配置切换。

## 16. 数据迁移

从第一个可发布数据库版本开始：

- Room schema version 必须显式增加。
- 禁止 destructive migration 作为正式默认策略。
- 测试数据库迁移。
- IMPLEMENT 报告记录 schema 版本变化。
