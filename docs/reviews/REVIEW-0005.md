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

---

# 第二轮复审（R2）

复审日期：2026-09-20
复审实现提交：`25cd436880a4bba57393004fa84571782d5d7456`
交接提交：`2b016e5e8232bfde535f494a44c4cbde4a14a038`
状态：**CHANGES_REQUIRED**

## R2-1. 第一轮问题复核

以下第一轮问题已经确认实质修复：

- BLOCKER-01 StablePage 生命周期：通过。WindowGate 进入 CHANGED / UNAVAILABLE 会增加 version；capture 状态、目标 package、异常 gate 变化会 invalidate Vision。
- BLOCKER-02 最新 UNKNOWN / AMBIGUOUS / 其他页面：通过。最新 detection 不是当前 MATCHED 页面时，不再继续输出旧 actionable StablePage。
- BLOCKER-03 windowBounds / contentViewport 分层：当前目标 Pad 范围内通过。已建立 WindowGeometry；真实 fixture viewport 改由私有 metadata 读取；synthetic test 覆盖两层坐标。
- BLOCKER-04 正向 Dry-Run：基本通过。DUNGEON_LIST 私有真实 fixture 可以形成 ALLOW_DRY_RUN，失焦 / resize / 缺 target 会 DENY，全程 0 gesture。
- REQUIRED-05 freshness/version：通过。Action Guard 已检查 viewportVersion 与 observedAt。
- REQUIRED-06 Template 最小闭环：基本通过。FIXED policy、threshold、TemplateEvidence、PageDetector required/negative 有测试。
- REQUIRED-07 free-attempt 可配置入口：接口已建立，但仍有安全问题，见 R2-BLOCKER-02。

## R2-BLOCKER-01：Action Guard 的安全策略仍由调用方提供，可绕过 SENSITIVE 规则

当前 `ActionIntent` 允许调用方传入 `riskLevel`，`ActionContext` 还允许调用方传入 `allowedPages` 与 `expectedPagesAfter`。

因此 `START_DUNGEON_FREE` 理论上可以被错误构造成 SAFE/NORMAL，从而跳过 FREE_STATE_NOT_CONFIRMED、DAILY_ALREADY_SUCCESS、PURCHASE_SIGNAL_PRESENT 等敏感门禁。调用方也可以错误扩大 allowedPages 或伪造 postcondition。

这不符合“Action Guard 是安全策略唯一事实来源”的设计目标。

### 修改要求

建立中心化 `ActionPolicyRegistry`，由 `ActionType` 唯一决定：riskLevel、allowedPages、requiredTargetId、expectedPagesAfter。`ActionIntent` 不再允许业务调用方降低 riskLevel；`ActionContext` 只放事实，不放用于降低风险的政策。

至少固定：`START_DUNGEON_FREE = SENSITIVE`，`PURCHASE = FORBIDDEN_AUTO`。未知或缺失 policy 默认 DENY。

### 必测

- START_DUNGEON_FREE 无法被 SAFE/NORMAL 绕过免费状态检查。
- PURCHASE 永远 FORBIDDEN_AUTO。
- 调用方不能自定义 allowedPages 绕过 PAGE_NOT_ALLOWED。
- 调用方不能伪造 expectedPagesAfter。

## R2-BLOCKER-02：FreeAttemptState 仍会被普通页面 OCR 误触发

当前 `ConfiguredVisionEngine` 把普通页面 OCR 与 semantic OCR 都加入同一个 `rawOcr`，最终调用 `freeAttemptState(rawOcr)`。

这意味着即使没有配置 FREE_ATTEMPT semantic ROI，只要普通页面 ROI 偶然识别到“免费(1/1)”，系统仍可能返回 AVAILABLE。

这与“没有显式 FREE_ATTEMPT ROI 就必须 UNKNOWN”的安全要求冲突。

### 修改要求

页面 OCR 与 semantic OCR 必须分流。FreeAttemptState 只能由 `SemanticOcrSignalType.FREE_ATTEMPT` 对应 ROI 的结果计算；没有该 semantic signal 时必须强制 UNKNOWN。

### 必测

- 无 FREE_ATTEMPT semantic 配置，但普通 OCR 出现“免费(1/1)” → UNKNOWN。
- 配置 FREE_ATTEMPT ROI 且命中 → AVAILABLE。
- FREE_ATTEMPT ROI OCR 失败 → UNKNOWN。
- 其他 semantic 类型不能污染 FreeAttemptState。

## R2-BLOCKER-03：ActionPlan 的 targetRect 仍是 OCR 搜索 ROI，不是真实交互目标

当前普通 OCR signal 命中后生成的 `OcrEvidence.rect` 使用整个配置 ROI，而不是 ML Kit 实际识别文字的 boundingBox。StablePage 的 `anchors["dungeon.anchor"]` 因此保存的是搜索区域。

随后 Action Guard 的 would-tap point 是这个搜索 ROI 的中心。它虽然暂时不会真的点击，但还不能证明未来动作坐标由真实交互目标证据产生。

另外当前 `OPEN_DUNGEON / dungeon.anchor` 使用“切换区域”作为 anchor；必须确认它确实是要执行动作的控件，而不是仅用于识别页面的静态文字。

### 修改要求

把 Page recognition anchor 与 Action target 分开。建议增加 `ActionTargetEvidence`。若使用 OCR，Action target rect 使用实际 line.boundingBox；更推荐真实按钮采用 template/visual target。Action Guard 还必须验证 targetRect 完全位于当前 contentViewport 内，否则 DENY `TARGET_OUTSIDE_VIEWPORT`。

### 必测

- 搜索 ROI 大于实际文字 rect 时，ActionPlan 使用实际目标 rect。
- targetRect 超出 contentViewport → DENY。
- 页面识别 anchor 存在但 action target 不存在 → TARGET_NOT_CONFIRMED。
- 真机 Dry-Run UI 展示真实 targetRect / tapPoint。

## R2-BLOCKER-04：用户 Stop 后 VisionWorker 在同一 ViewModel 生命周期无法重新启动

`MainViewModel.stop()` 会调用 `visionWorker.stop()`，但 `VisionWorker.start()` 只在 ViewModel init 中调用一次。

因此 Stop 后重新申请 capture、重新环境检查时，VisionWorker 不会恢复，除非整个 Activity/ViewModel 被重建。

### 修改要求

推荐 Stop 时只 invalidate Vision，不 cancel worker；只在 `onCleared()` 真正 stop。或者增加明确的 `ensureVisionWorkerStarted()`，在 capture重新 Active / environment check 时恢复。

### 必测

同一 App/ViewModel 生命周期内：Vision正常 → Stop → 重新申请 capture → Environment Check → 手动进入已知页面 → Vision重新产生 detection/stablePage。不得通过重启 App 绕过。

## R2-REQUIRED-05：TemplateDefinition.negative 仍未成为单一配置事实来源

当前 `TemplateDefinition(negative=true)` 本身没有行为；是否为 negative signal 仍由 PageDefinition 另行配置。两个来源可能不一致。

二选一：

- negative 归 PageDefinition：从 TemplateDefinition / manifest 删除该字段；或
- negative 归 TemplateDefinition：配置加载时自动注册到对应页面 negative signals，并做一致性校验。

不要保留两个互相独立的真相来源。

## R2-REQUIRED-06：contentViewport 后续需要真实校准入口

当前目标 Pad 已实测可按 `windowBounds == contentViewport` 工作，所以本轮不作为 blocker。但生产代码仍只会把 windowBounds 同值写入 contentViewport profile。

若未来 ZUI 出现标题栏/inset，应用必须能接受不同 contentViewport。可在后续增加本地 profile 加载/校准入口；不要猜 inset。

## R2-7. 第二轮结论

`REQ-0005 = CHANGES_REQUIRED`

当前下一轮只修：

1. ActionPolicy 中心化，调用方不能降低 risk/allowedPages。
2. FreeAttemptState 只能来自显式 FREE_ATTEMPT semantic ROI。
3. Action target 使用真实交互目标 rect，并校验在 contentViewport 内。
4. Stop 后 Vision 可在同一生命周期重新工作。
5. 统一 Template negative 的配置事实来源。

不得新增 REQ-0006，不得接真实 Action Executor，不得对游戏执行点击/滑动/返回，不得开始副本自动化或角色切换。

修完后重新执行 JVM tests、connected Android tests、私有 fixture tests、Stop→Restart Vision 生命周期测试、ActionPolicy bypass tests、Semantic FreeAttempt isolation tests、targetRect containment tests、Pad Inspector / Vision Sampler、安全扫描和 `git diff --check`，再更新 IMPLEMENT-0005 / LATEST / LATEST.patch 交给第三轮复审。


---

# 第三轮复审（R3）

复审日期：2026-09-20  
复审实现提交：`fd0f318daa8135ea33393dba36d45ec68b3d64c6`  
交接提交：`fde90cb48414aa369d80866217d49fe260d35995`  
状态：**ACCEPTED**

## R3-1. R2 问题最终复核

- **R2-BLOCKER-01 ActionPolicy 中心化：PASS**
  - `ActionIntent` 只保留 `ActionType`。
  - riskLevel、allowedPages、requiredTargetId、expectedPagesAfter 统一由 `ActionPolicyRegistry` 决定。
  - `START_DUNGEON_FREE` 固定为 SENSITIVE。
  - `PURCHASE` 固定为 FORBIDDEN_AUTO。
  - 调用方已无法通过参数降低动作风险或扩大允许页面。

- **R2-BLOCKER-02 FreeAttempt semantic OCR 隔离：PASS**
  - 普通页面 OCR 不再参与 FreeAttemptState 计算。
  - 只有 `SemanticOcrSignalType.FREE_ATTEMPT` 的显式 ROI 才可提供免费次数证据。
  - 未配置或 OCR 失败保持 UNKNOWN。
  - GENERIC semantic signal 不污染 FreeAttemptState。

- **R2-BLOCKER-03 ActionTargetEvidence：PASS**
  - 页面 evidence 与 action target 使用独立通道。
  - action target rect 来自实际 OCR line boundingBox，不再使用整个搜索 ROI。
  - Action Guard 检查 targetRect 必须完整位于 contentViewport 内。
  - 缺失 target 返回 TARGET_NOT_CONFIRMED；越界返回 TARGET_OUTSIDE_VIEWPORT。
  - 真机私有 fixture 已形成实际 targetRect / tapPoint 的 ALLOW_DRY_RUN。

- **R2-BLOCKER-04 Stop → Restart Vision 生命周期：PASS**
  - 用户 Stop 只 invalidate Vision，不再取消 VisionWorker。
  - `onCleared()` 才真正 stop worker。
  - 同一 worker 生命周期可在新 capture 帧到达后重新形成 detection / stablePage。
  - 自动测试已覆盖 invalidate → 新帧 → 稳定页恢复。

- **R2-REQUIRED-05 Template negative 单一事实来源：PASS**
  - TemplateDefinition / manifest / sampler 中重复 negative 字段已删除。
  - negative 规则统一归 PageDefinition 管理。

## R3-2. 回归与安全验证

本轮记录的最终验证：

- JVM tests：31 项通过。
- connected Android tests：通过。
- 私有真实页面 fixture：通过。
- SETTINGS / CHARACTER_SELECT / DUNGEON_LIST 继续正确识别。
- DUNGEON_LIST 正向 Vision → ActionGuard → ALLOW_DRY_RUN 成功。
- 同场景失焦、WindowGate 变化、缺 target 均正确 DENY。
- Pad Inspector / Vision Sampler 测试通过。
- 安全扫描与 `git diff --check` 通过。
- 全程没有新增或执行游戏 gesture。

## R3-3. 非阻塞技术债

以下不阻塞 REQ-0005，但在任何真实 Action Executor 接入前必须继续处理：

1. 当前 action target 使用按钮文字 boundingBox，而不是完整按钮外框。真实点击阶段优先使用已审核 template / accessibility bounds / 明确按钮视觉区域。
2. `ActionTargetEvidence.confidence` 当前在进入 `VisionMetrics.actionTargets` 后只保留 rect。真实 Executor 前应保留 confidence/source，并允许 policy 设置最低 target confidence。
3. 当前演示政策 `OPEN_DUNGEON → dungeon.switch_region` 主要用于证明 Dry-Run 链路；接入真实业务动作前应校正 ActionType 与实际按钮语义，避免“动作名与控件含义不一致”。
4. 当前目标 Pad 实测 contentViewport 可与 windowBounds 同值；若未来出现标题栏/inset，必须增加真实本地校准入口，不得猜 inset。
5. 免费次数真实 ROI 尚未采集，必须继续保持 UNKNOWN，直到取得真实样本。
6. 真实模板仍为本地私有 PENDING 资产；LIMITED_SCALE 尚未实现。
7. Vision Debug 后续可继续增强 evidence/ROI/OCR 细节展示，但不影响本阶段识别与 Guard Dry-Run 验收。

## R3-4. 最终结论

```text
REQ-0005 = ACCEPTED
允许创建 Pull Request：
feat/req-0005-vision-foundation → main

暂不接入真实游戏输入。
下一阶段开始前，必须先设计真实 Action Executor 的额外安全门禁与首个最小可控操作范围。
```

REQ-0005 的目标已经达到：

```text
Frame
→ contentViewport / ROI
→ OCR / Template evidence
→ PageDetector
→ StablePage
→ ActionTargetEvidence
→ ActionPolicy
→ ActionGuard
→ ALLOW_DRY_RUN / DENY
```

并保持全程 0 游戏 gesture。
