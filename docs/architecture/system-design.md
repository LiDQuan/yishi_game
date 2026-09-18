# 系统架构设计

对应需求：REQ-0003

## 1. 设计原则

1. 安全优先于完成率。
2. 识别后操作，而不是按时间盲点。
3. 业务流程配置化。
4. 核心运行离线化。
5. 状态可恢复。
6. 日志可解释。
7. 游戏更新后尽量替换模板/配置，而不是重写核心。
8. ADB 是开发工具，不是正式运行依赖。

## 2. 总体结构

```text
┌──────────────── Android APK ────────────────┐
│ UI / Dashboard                              │
│        ↓                                    │
│ Automation Orchestrator                     │
│        ↓                                    │
│ State Machine ───── Scheduler               │
│        ↓              ↓                     │
│ Action Guard       Role / Task Plan         │
│        ↓                                    │
│ Accessibility Controller                    │
│        ↑                                    │
│ Page Detector / Vision / OCR                │
│        ↑                                    │
│ Screen Capture (MediaProjection)            │
│                                             │
│ Battle Strategy Engine                      │
│ Local Database (Room)                       │
│ Diagnostics / Recovery                      │
└─────────────────────────────────────────────┘

Mac 开发端：
ADB ↔ Pad Inspector
Web/公开资料 ↔ Skill Collector
Codex ↔ GitHub
ChatGPT ↔ GitHub
```

## 3. Android 模块

### 3.1 UI

推荐 Compose。

UI 只展示状态和发出用户意图，不直接点击游戏。

主要 ViewModel：

- DashboardViewModel
- RoleViewModel
- PlanViewModel
- HistoryViewModel
- DiagnosticsViewModel

### 3.2 Automation Orchestrator

唯一可以启动/暂停/停止自动化流程的上层控制器。

职责：

- 启动 Precheck。
- 持有当前 Session。
- 驱动状态机。
- 管理取消。
- 协调调度器、页面识别和动作控制。
- 保证同一时间只有一个自动化 Session。

### 3.3 State Machine

状态机是系统事实来源。

任何异步回调不能绕过状态机直接改变业务进度。

详见 `state-machine.md`。

### 3.4 Action Guard

所有真实输入统一走 Action Guard。

建议接口语义：

```text
execute(action, expectedPage, target, postCondition, riskLevel)
```

流程：

- precondition
- perception
- risk check
- action
- postcondition
- retry
- recovery

RiskLevel：

- SAFE：返回、打开普通菜单。
- NORMAL：进入副本等无消费行为。
- SENSITIVE：可能涉及次数、资源、奖励确认。
- FORBIDDEN_AUTO：购买、付费、未知消费。

FORBIDDEN_AUTO 永不自动执行。

### 3.5 Accessibility Controller

封装：

- tap(x, y)
- swipe
- global back
- windows
- bounds
- 可访问节点查询（若游戏可暴露）

游戏引擎画面很可能无法提供完整语义节点，因此 Accessibility 主要承担输入与系统窗口信息，页面语义依赖视觉模块。

### 3.6 Screen Capture

对上层提供标准 Frame：

- width
- height
- timestamp
- bitmap/image buffer
- orientation
- crop(ROI)

不能让每个识别器自己重复申请 MediaProjection。

### 3.7 Vision

#### Template Matcher

用途：

- 固定 UI 图标。
- 按钮状态。
- Boss 机制图标。
- 技能 READY/CD 模板。

要求：

- ROI。
- 多尺度策略仅在必要时启用。
- 输出 confidence。
- 模板带版本和适用 viewport 信息。

#### OCR

只处理需要文字判断的 ROI：

- 免费次数。
- 等级。
- 特定任务文字。
- Boss 读条文字（适用时）。

避免整屏持续 OCR。

### 3.8 Page Detector

一个 PageDefinition 由多个 Signal 组合：

```text
required signals
optional signals
negative signals
threshold
```

输出：

```text
PageMatch(pageId, confidence, evidences)
```

页面识别不确定时返回 UNKNOWN。

### 3.9 Scheduler

输入：

- 当前日期。
- 角色列表。
- 每日完成状态。
- 副本计划。
- 日常计划。

输出下一个 WorkItem。

不得依赖 UI 层保存“今天做到第几个角色”。

### 3.10 Task Engine

将日常任务描述为可版本化流程。

节点建议：

- Navigate
- Detect
- Tap
- Swipe
- WaitFor
- Branch
- MarkSuccess
- Skip
- Fail

每个节点都有 timeout、retry、risk。

### 3.11 Battle Engine

三种模式：

- AUTO_DETECT
- NATIVE_AUTO
- SCRIPT_CAST

SCRIPT_CAST：

```text
Frame
→ Perception
→ BattleSnapshot
→ Strategy Evaluate
→ Candidate Actions
→ Priority/Guard
→ Cast
→ Observe
```

BattleSnapshot 可以包含：

- playerHpRatio
- bossHpRatio
- skill states
- buffs/debuffs
- boss cast
- phase
- enemy count
- confidence map

### 3.12 Strategy Engine

策略与视觉识别分离。

策略只消费 BattleSnapshot，不直接截图。

这使同一攻略规则可以复用到不同分辨率/模板版本。

### 3.13 Persistence

Room。

数据库只保存结构化状态，不存大量图片。

截图存文件系统，并由 ErrorEvent 保存相对引用。

### 3.14 Diagnostics

每个事件使用统一 envelope：

```json
{
  "timestamp": 0,
  "sessionId": "...",
  "state": "...",
  "eventType": "...",
  "result": "...",
  "errorCode": null,
  "metadata": {}
}
```

metadata 必须经过敏感字段过滤。

## 4. 电脑模式窗口标准化

这是设备相关模块，不应污染通用业务层。

建议接口：

```text
WindowProvider
WindowNormalizer
ViewportMapper
```

WindowProvider：

- 读取 bounds。
- 检测变动。

WindowNormalizer：

- 尝试将窗口恢复到配置。
- 返回 SUCCESS / UNSUPPORTED / FAILED。

ViewportMapper：

- 将规范化的相对坐标映射到当前内容区。
- 处理标题栏/边框与内容区偏移。

运行过程中一旦 bounds 变化：

```text
PAUSE
→ WINDOW_CHECK
→ NORMALIZE
→ PAGE_REIDENTIFY
→ RESUME 或 WAIT_USER
```

## 5. 配置分层

### Public Config

仓库可保存：

- schema。
- 默认参数。
- 模板版本定义。
- 示例任务。
- 攻略和技能公开数据。

### Local Private Config

Mac / Pad 本地：

- 真实设备参数。
- 私有仓库 Token。
- ADB endpoint。
- 用户自定义敏感角色名称（若需要）。
- 原始截图路径。

## 6. 线程与并发

建议：

- 一个自动化主协程。
- 视觉识别使用受控 Dispatcher。
- 所有输入动作串行。
- STOP 必须可取消当前等待。
- 同一 Action 不允许并发重复点击。

## 7. 错误分类

建议 errorCode 前缀：

- ENV_
- PERMISSION_
- WINDOW_
- PAGE_
- ACTION_
- DUNGEON_
- BATTLE_
- TASK_
- DB_
- SYNC_

例：

```text
WINDOW_BOUNDS_CHANGED
PAGE_UNKNOWN
DUNGEON_FREE_STATE_NOT_FOUND
ACTION_POSTCONDITION_TIMEOUT
```

## 8. 可测试性

每一层必须可替换：

- Fake ScreenSource
- Fake PageDetector
- Fake ActionController
- InMemory Repository
- Fake Clock

这样状态机和调度器可以在没有 Pad 的 CI 中测试。

真机测试只验证设备相关部分和真实 UI 集成。

## 9. 技术风险

### 自由窗口 resize

普通 Android 权限不保证能 resize 其他 App。必须实机验证并保留安全降级。

### Unity/游戏 UI 节点不可见

预期大量语义不可由 UIAutomator直接读取，因此视觉系统是主方案。

### UI 更新

使用多信号页面识别和模板版本控制，禁止单模板决定高风险动作。

### 电池/后台限制

后续需要针对 ZUI 的后台限制、前台服务行为进行实测，不在 M0 凭空配置。
