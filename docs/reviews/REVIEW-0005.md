# REVIEW-0005：M1 视觉识别与 Action Guard 第一轮复审

状态：CHANGES_REQUIRED  
审查对象：`feat/req-0005-vision-foundation`  
实现提交：`560108d020d1429bda72a70c71de09412a6dac64`  
交接提交：`c7b30fb241e165266fde52f587a23b96aabf0fda`  
对应需求：`REQ-0005`

## 1. 总体结论

本轮实现已经形成真实可运行的 M1 雏形，以下部分认可：

- Compose 不再订阅完整 RGBA ByteArray。
- Capture 与 Vision FPS 已解耦。
- VisionWorker 在后台线程运行。
- OCR 只在配置 ROI 内执行，没有持续整屏 OCR。
- ViewportMapper 使用 normalized rect。
- PageDetector 保留 MATCHED / AMBIGUOUS / UNKNOWN。
- 真机已识别 SETTINGS、CHARACTER_SELECT、DUNGEON_LIST 三类页面。
- 免费次数没有真实样本时保持 UNKNOWN，没有猜测 AVAILABLE/EXHAUSTED。
- Action Guard 没有直接调用 AccessibilityController。
- 本轮没有对游戏内容区发送真实输入。
- 私有 fixture 未提交公开仓库。
- JVM、Android、Pad Inspector、安全扫描均有执行记录。
- 5 分钟性能基线已经取得。

但当前存在 4 个会影响未来真实动作安全性的阻塞问题，以及 3 个应在本轮顺手修正的问题。

本轮结论：

```text
REQ-0005 = CHANGES_REQUIRED
DO NOT MERGE TO main
```

Codex继续使用原分支修复，不新增 REQ-0006，不开始真实游戏点击。

---

# BLOCKER-01：StablePage 生命周期没有跟随窗口变化、采集重启真正失效

## 问题

`VisionWorker` 用：

```kotlin
stableTracker.add(
    result.detection,
    viewportVersion(),
    System.currentTimeMillis(),
)
```

来决定是否清空 StablePage。

但当前 `WindowMonitor.version` 只在：

```kotlin
accept()
clear()
```

时增加。

`observe()` 返回：

```text
CHANGED
UNAVAILABLE
```

时 version 不变。

因此：

```text
窗口已变化
→ WindowGate = CHANGED
→ 状态机暂停
→ 但 StablePageTracker 的 viewportVersion 没变
→ 历史页面仍然保留
```

另一个问题是 capture session 结束或重启时：

```kotlin
frameStore.clear()
```

只清帧，不会清：

```text
VisionWorker.metrics.stablePage
StablePageTracker.history
```

所以旧 capture session 的 StablePage 可以残留到新 session。

这违反 REQ-0005 / vision-pipeline 明确约定：

- WindowGate changed → StablePage 失效。
- capture restarted → StablePage 失效。
- viewport changed → StablePage 失效。
- 旧 ROI/页面结果不得继续作为动作依据。

## 影响

当前 Action Guard 虽然会因为 WindowGate != MATCHED 或 capture inactive 而 DENY，但在重新环境检查、重新 accept 新窗口后，到下一次 VisionWorker 更新之前存在短暂窗口：

```text
新窗口已 MATCHED
+
旧 stablePage 仍存在
```

未来接真实 Executor 时，这属于 TOCTOU 类型的安全问题。

## 修改意见

不要只依赖 `WindowMonitor.version`。

建议建立独立的 `VisionEpoch` / `EnvironmentGeneration`，以下事件必须 bump generation 并立即 invalidate：

- WindowGate 从 MATCHED 变为 CHANGED。
- WindowGate 变 UNAVAILABLE。
- viewport/profile 被重新接受。
- capture session stop/fail/restart。
- target package 改变。
- template set / page definition version 改变。

至少在 M1 可以由 MainViewModel 显式调用：

```kotlin
visionWorker.invalidate()
```

但更推荐把 generation 放入 VisionFacts，并让 Action Guard比较。

## 参考代码

```kotlin
data class VisionGeneration(
    val viewportVersion: Long,
    val captureSessionId: Long,
    val configVersion: String,
)

data class StablePage(
    val pageId: String,
    val confidence: Float,
    val observedAt: Long,
    val generation: VisionGeneration,
)
```

Action Guard：

```kotlin
if (page.generation != context.currentVisionGeneration) {
    return deny(GuardDenyReason.PAGE_STALE)
}
```

并对 capture stop/restart、WindowGate changed 立即：

```kotlin
visionWorker.invalidate()
```

## 建议测试

1. MATCHED 下形成 SETTINGS StablePage。
2. 窗口 resize → StablePage 立即变 null。
3. 窗口 unavailable → StablePage 立即变 null。
4. capture stop → StablePage 立即变 null。
5. capture restart → 不能继承上一 session 的 2/3 history。
6. 新 viewport accept 后、第一轮新视觉结果前，Action Guard 必须 DENY PAGE_STALE/PAGE_NOT_STABLE。

---

# BLOCKER-02：StablePage 可以在“最新帧已经 UNKNOWN/其他页面”时继续返回旧页面

## 问题

当前 StablePageTracker：

```kotlin
val matches = history.filterIsInstance<PageDetection.Matched>()
val group = matches.groupBy { it.pageId }.maxByOrNull { it.value.size }
if (group.value.size < requiredMatches) return null
return StablePage(group.key, ...)
```

例如：

```text
frame 1 = SETTINGS
frame 2 = SETTINGS
frame 3 = UNKNOWN
```

最近 3 帧仍有 2 个 SETTINGS，因此 frame 3 仍返回：

```text
StablePage = SETTINGS
```

甚至：

```text
SETTINGS
SETTINGS
CHARACTER_SELECT
```

最新帧已经匹配 CHARACTER_SELECT，仍可能返回 SETTINGS。

这会把当前 UNKNOWN / 新页面强行覆盖成旧的“稳定页面”，与本项目“UNKNOWN优先”的安全原则冲突。

## 影响

Vision Debug 看起来仍在旧页面。

更重要的是，未来 Action Guard 只读取 `stablePage`，可能在页面切换后的短窗口中按旧页面规则放行动作。

2 FPS 时这不是微秒级竞态，可能持续 0.5 秒以上。

## 修改意见

“稳定显示”与“可用于动作”可以分开。

最低要求：

- 最新 detection 必须是 MATCHED。
- 最新 MATCHED.pageId 必须等于 StablePage.pageId。
- UNKNOWN / AMBIGUOUS 当前帧不能生成 Actionable StablePage。

推荐：

```kotlin
val latest = history.lastOrNull()
if (latest !is PageDetection.Matched) return null

val samePageCount = history.count {
    it is PageDetection.Matched && it.pageId == latest.pageId
}

if (samePageCount < requiredMatches) return null
return StablePage(latest.pageId, ...)
```

如希望 UI 在一帧 UNKNOWN 时仍显示“上一次稳定页”，可以另设：

```text
lastKnownStablePage
actionableStablePage
```

Action Guard 只能消费后者。

## 建议测试

必须新增：

```text
SETTINGS, SETTINGS, UNKNOWN → actionable StablePage = null
SETTINGS, SETTINGS, AMBIGUOUS → null
SETTINGS, SETTINGS, CHARACTER_SELECT → null
SETTINGS, UNKNOWN, SETTINGS → 可按 N/M 规则重新确认
```

---

# BLOCKER-03：当前把 Accessibility windowBounds 直接当 contentViewport，未实现 REQ-0005 要求的两层坐标模型

## 问题

当前实际 Vision viewport 来源：

```kotlin
viewport = {
    mutableUiState.value.windowBounds ?: lastKnownVisionViewport()
}
```

且 `rememberVisionViewport()` 保存的仍然是：

```text
targetWindow.bounds
```

也就是说当前模型实际上是：

```text
windowBounds == contentViewport
```

但 REQ-0005 明确要求区分：

```text
Screen coordinates
Window bounds
Game content viewport
Normalized viewport coordinates
ROI coordinates
```

联想电脑模式自由窗口可能存在标题栏、边框和系统装饰。

当前没有：

- content insets。
- ContentViewportProfile。
- 本地校准。
- “已证明 windowBounds 等于 contentViewport”的设备实测结论。

此外，私有 fixture 仪器测试在公开源码中硬编码：

```kotlin
WindowBounds(99, 7, 1119, 1717)
```

这等于把某次真实本机 viewport 直接写进测试代码，而不是从私有 fixture metadata/profile 读取。

## 影响

当前三页能识别，只能证明这一组截图+这一组坐标能工作。

无法证明：

- 窗口标题栏变化后 ROI 正确。
- 不同自由窗口大小正确。
- 系统装饰改变后正确。
- 真实动作 targetRect 与游戏内容坐标一致。

这会直接影响后续点击坐标安全。

## 修改意见

建立明确：

```kotlin
data class WindowGeometry(
    val windowBounds: WindowBounds,
    val contentViewport: WindowBounds,
    val profileVersion: Long,
)
```

如果当前 ZUI 实测证明 Accessibility bounds 就是游戏 content bounds，也必须：

1. 把这个结论写入 IMPLEMENT。
2. 在模型层仍保留 `contentViewport` 概念。
3. 给出验证方法。

如果无法自动确定：

- 使用一次性本地 ContentViewportProfile。
- profile 存本机私有配置。
- 公开仓库只保留 schema/example。

私有真实 fixture test 的 viewport 必须从：

```text
fixtureDir/<fixture>.json
或 local viewport profile
```

读取，不得硬编码真实值在测试源码。

## 建议测试

至少：

1. windowBounds 与 contentViewport 不同的 synthetic test。
2. normalized ROI 映射必须基于 contentViewport。
3. 改变标题栏/边框 inset，ROI仍命中同一内容位置。
4. 私有 fixture 从私有 metadata 读 viewport。
5. contentViewport 未确认 → Vision 可以 UNKNOWN，Action Guard 必须 VIEWPORT_INVALID。

---

# BLOCKER-04：Vision Debug 中唯一的 Action Guard 生产路径实际上永远不能 ALLOW_DRY_RUN

## 问题

当前 UI 只有：

```text
评估 OPEN_SETTINGS Dry-Run
```

但 `evaluateOpenSettingsDryRun()` 构造：

```kotlin
allowedPages = setOf("MAIN")
targetRect = null
```

与此同时当前 VisionConfiguration 只定义：

```text
SETTINGS
CHARACTER_SELECT
DUNGEON_LIST
```

没有 MAIN。

Action Guard 又明确要求：

```kotlin
val page = stablePage ?: DENY
if (page.pageId !in allowedPages) DENY
val rect = targetRect ?: DENY(TARGET_NOT_CONFIRMED)
```

因此这条实际 UI 路径在当前代码下**不可能返回 ALLOW_DRY_RUN**。

单元测试能构造一个假的 targetRect 并 ALLOW，并不等于生产 Vision → Guard 链路已经打通。

## 影响

REQ-0005 的核心目标之一是证明：

```text
真实 Vision Facts
→ Action Guard
→ 应该执行 / 不应该执行
```

当前只能证明“拒绝路径可用”，不能证明真实识别证据可以形成一个正向 Dry-Run ActionPlan。

更重要的是，当前 VisionFacts 没有向业务层暴露“动作目标 anchor rect”，后续真实 ActionPlan 没有可靠 targetRect 来源。

## 修改意见

不需要开始真实点击。

只需要选一个**用户手动可到达、已采样且安全的真实页面**，建立一个只读正向 dry-run 场景。

例如：

- 在真实页面中确认一个 SAFE/NORMAL 的按钮 anchor。
- Vision 输出该 anchor 的 evidence rect。
- Action Guard 从 evidence 生成 targetRect。
- 返回 ALLOW_DRY_RUN。
- UI 显示“Would tap rect / point”。
- **绝不调用 gesture**。

不要猜按钮。

如果本轮现有 3 页没有合适安全 anchor，就由用户手动打开一个合适页面后采样。

## 建议测试

真机至少形成：

```text
Case A：
真实页面 + 正确 anchor + 所有全局门禁通过
→ ALLOW_DRY_RUN

Case B：
同一页面失焦
→ DENY TARGET_NOT_ACTIVE

Case C：
窗口 resize
→ DENY WINDOW_NOT_MATCHED

Case D：
去掉 target evidence
→ DENY TARGET_NOT_CONFIRMED
```

并确认全程 0 gesture。

---

# REQUIRED-05：Action Guard 还缺少 StablePage generation/freshness 检查

## 问题

ActionContext 当前只有：

```text
viewportValid: Boolean
stablePage: StablePage?
```

StablePage 内虽然有 `viewportVersion` 和 `observedAt`，但 Action Guard 完全不检查。

因此：

- StablePage 是否属于当前 viewport version 不知道。
- StablePage 是否已经过期不知道。

## 影响

未来即使 BLOCKER-01 做了 invalidate，也不应该只依赖外部调用“记得清”。

Action Guard作为最后一道安全门，应自行验证 evidence freshness。

## 修改意见

ActionContext 增加：

```text
currentViewportVersion
now
maxStablePageAgeMs
```

并拒绝：

```text
stablePage.viewportVersion != currentViewportVersion
now - stablePage.observedAt > maxStablePageAgeMs
```

新增：

```text
PAGE_STALE
PAGE_VIEWPORT_MISMATCH
```

---

# REQUIRED-06：TemplateMatcher 目前只是像素差工具，TemplateDefinition 的 threshold / negative / scalePolicy 未真正接入

## 问题

当前 `TemplateDefinition` 包含：

```text
threshold
negative
```

manifest 还有：

```text
scalePolicy
```

但 `TemplateMatcher.compare()` 接口只接：

```text
id
candidate
template
rect
```

不会：

- 读取 threshold。
- 应用 negative。
- 处理 scalePolicy。
- 将 TemplateDefinition 转成 VisionEvidence 规则。
- 接入 ConfiguredVisionEngine。

当前生产页面识别全部是 OCR。

单测名称：

```text
template confidence supports per-template threshold
```

实际上只是验证 `different.confidence < .9`，并没有测试“per-template threshold”行为。

## 影响

报告中“TemplateMatcher 已实现”容易让下一阶段误以为模板管线已经可用。

一旦开始消费黑名单/购买弹窗识别，negative template 是关键安全能力。

## 修改意见

本轮至少完成一个最小闭环：

```text
TemplateDefinition
→ ROI crop
→ matcher
→ threshold
→ TemplateEvidence
→ PageDetector required/negative
```

scalePolicy 如果本轮只支持 FIXED，应明确：

```text
FIXED supported
LIMITED_SCALE not yet supported
```

不要在 manifest 写已支持但代码未实现的策略。

---

# REQUIRED-07：FreeAttemptState 单元函数正确，但生产 VisionConfiguration 尚无免费次数 ROI

## 问题

`freeAttemptState()` 本身处理：

```text
免费(1/1) → AVAILABLE
其他 → UNKNOWN
```

这是正确的。

但 `ConfiguredVisionEngine` 的 `rawOcr` 只来自当前 6 个页面标题/锚点 ROI，没有专门的 free-attempt ROI/信号。

因此现有生产配置即使未来屏幕上出现“免费(1/1)”，只要文本不落在这 6 个 ROI 中，也无法检测。

当前没有真实样本，所以本轮不要求猜 ROI。

## 修改意见

增加可配置的：

```text
semantic OCR facts / freeAttemptSignal
```

允许本机私有配置在未来真实样本出现后填入 ROI。

默认无配置时保持 UNKNOWN。

不要硬编码猜测位置。

---

# 8. 通过项

以下本轮确认通过：

- FrameStore 避免 capture 每帧新建完整 ByteArray；UI 只观察轻量 FrameMetadata。
- Vision snapshot 目前仍会按 Vision FPS 复制整屏 buffer，但已从 capture FPS 降到约 2 FPS，M1 可接受；后续可继续 ROI copy。
- OCR 没有整屏持续识别。
- PageDetector required/optional/negative 基础逻辑存在。
- UNKNOWN / AMBIGUOUS 是显式类型。
- “免费未识别 = UNKNOWN”正确。
- FORBIDDEN_AUTO 在 Action Guard 最前面永久 DENY。
- 无真实游戏 gesture 路径新增。
- 私有截图/fixture 没有提交公开仓库。
- 真实 3 页 OCR 验证有测试证据。
- 5 分钟 PSS 没有明显单调无界增长，当前作为性能基线可接受。
- Vision Sampler 的输出目录/文件权限方向正确。

---

# 9. Codex 修订要求

继续使用：

`feat/req-0005-vision-foundation`

不要：

- 新增 REQ-0006。
- 开始副本自动点击。
- 开始角色自动切换。
- 开始战斗。
- 重写已有历史。

本轮必须修：

1. BLOCKER-01 StablePage 环境/采集生命周期。
2. BLOCKER-02 最新 UNKNOWN/其他页面不能继续作为 actionable old StablePage。
3. BLOCKER-03 contentViewport 与 windowBounds 分层。
4. BLOCKER-04 至少一条真实 Vision → ActionGuard → ALLOW_DRY_RUN 正向链路。
5. REQUIRED-05 Action Guard freshness/version 自检。
6. REQUIRED-06 TemplateDefinition → Matcher → PageDetector 最小闭环。
7. REQUIRED-07 FreeAttempt 可配置语义信号入口，不猜 ROI。

修完后重新执行：

- JVM unit tests。
- connectedDebugAndroidTest。
- 私有真实页面 fixture tests。
- Pad Inspector tests。
- Vision Sampler tests。
- security scan。
- git diff --check。

真机至少验证：

- 3 类页面仍可稳定识别。
- 当前页 UNKNOWN 时 actionable StablePage 为空。
- resize 后 StablePage 立即失效。
- capture restart 后 StablePage 不继承。
- contentViewport/profile 正确。
- 至少 1 个真实页面产生 ALLOW_DRY_RUN。
- 同场景失焦/resize/缺 anchor 后均 DENY。
- 全程 0 游戏 gesture。

更新：

- `codex/reports/IMPLEMENT-0005.md`
- `codex/handoff/LATEST.md`
- `codex/handoff/LATEST.patch`

然后交给 ChatGPT 第二轮 Review。
