# REVIEW-0004：Android M0 基础设施复审

状态：CHANGES_REQUIRED  
审查对象：`feat/req-0004-android-foundation`  
实现提交：`799c044380766dbb13a234665d919fc842136bed`  
交接提交：`791a0cfee6ae4e9c02187b84f480c404048f0fb2`  
对应需求：`REQ-0004`

## 1. 总体结论

本轮实现方向正确，M0 的主体骨架已经形成：

- Android / Compose 工程可构建。
- AccessibilityService、MediaProjection、Room、状态机、Pad Inspector 均已落地。
- 没有提前实现副本、OCR、模板匹配或游戏点击。
- DailyExecution 唯一约束、Room schema 导出和公开仓库安全扫描方向正确。
- Codex提交的 IMPLEMENT / LATEST 信息完整，功能分支与主分支保持清晰。

但当前仍存在数个会直接影响后续自动化安全性和可扩展性的基础问题。因此本轮暂不验收，不应合并 main。

阻塞项：4 个。  
测试补强项：1 个。  
建议改进项：1 个。

---

## 2. BLOCKER-01：MediaProjection“采到一帧”但没有把帧交给后续识别层

### 问题

`MediaProjectionCaptureService` 在 `ImageReader.OnImageAvailableListener` 中：

```kotlin
val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
image.close()
complete(width, height)
```

当前实现只证明系统回调到了一张 Image，然后立即关闭。对外暴露的 `ScreenCaptureState.Captured` 也只有 width / height。

因此当前上层拿不到：

- RGBA / Bitmap。
- 可供模板识别的像素。
- 可供 OCR 的图像。
- ROI crop。
- frame timestamp。

这不满足 REQ-0004 中“能获取一帧当前屏幕或目标内容截图供后续识别使用”的核心语义。

另一个更重要的问题是：服务完成一帧后立刻 `projection.stop()`。Android 14+ 的 MediaProjection 同一次授权会话应使用一个 MediaProjection / VirtualDisplay 捕获会话；如果未来每个识别帧都重新申请授权，自动化无法工作。

### 影响

下一阶段实现页面识别时必须重构整个 capture 接口，M0 的“截图能力”不能被视觉层复用。

同时，当前 `Captured` 状态会被 `runEnvironmentCheck()` 当作“采集已准备”，但此时 MediaProjection 已经停止，实际上并不存在可继续取帧的活动采集会话。

### 修改意见

把“权限验证”和“可持续读取帧”分开。

建议建立：

```text
ScreenCaptureController
  ├─ permissionState
  ├─ sessionState
  ├─ startSession(resultCode, data)
  ├─ latestFrame / captureFrame()
  └─ stopSession()
```

一个用户授权对应一个捕获 Session。Session 内只创建一个 VirtualDisplay，持续向 ImageReader 提供帧，直到用户停止自动化、系统撤销投屏或窗口配置需要更新。

M0 可以仍然只在 UI 中显示“成功取得一帧”，但必须至少实际把该帧转成可消费的 Frame 对象。

### 参考代码

```kotlin
data class ScreenFrame(
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val timestampNanos: Long,
    val rgba: ByteArray,
)

interface ScreenFrameSource {
    val sessionState: StateFlow<CaptureSessionState>
    val latestFrame: StateFlow<ScreenFrame?>

    fun start(resultCode: Int, data: Intent)
    fun stop()
}
```

ImageReader 回调至少应先复制 Plane buffer，再关闭 Image：

```kotlin
val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
try {
    val plane = image.planes.first()
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    mutableLatestFrame.value = ScreenFrame(
        width = image.width,
        height = image.height,
        rowStride = plane.rowStride,
        pixelStride = plane.pixelStride,
        timestampNanos = image.timestamp,
        rgba = bytes,
    )
} finally {
    image.close()
}
```

后续视觉层再负责处理 row padding / crop / Bitmap 转换，不要在业务状态机里处理。

### 建议测试

1. 真机授权一次后连续读取至少 20 帧，不再次弹授权。
2. 每帧 width / height / timestamp 有效。
3. 至少验证一张帧的非空像素数据，而不仅是 ImageReader 有回调。
4. stop 后不再产生帧。
5. 系统调用 MediaProjection.Callback.onStop 后进入明确的 NotReady/Stopped 状态。
6. Android 14+ 不重复对同一 MediaProjection 调用 createVirtualDisplay。

---

## 3. BLOCKER-02：目标游戏“前台检测”目前依赖最近一次 AccessibilityEvent，存在陈旧状态

### 问题

`GameAccessibilityService.onAccessibilityEvent()`：

```kotlin
event?.packageName?.toString()?.let { mutableForegroundPackage.value = it }
```

这只能表示“最近收到的相关 AccessibilityEvent 来自哪个包”，不能可靠等价于“当前真正拥有输入焦点的前台窗口”。

而 `MainViewModel.refresh()` 使用：

```kotlin
targetDetected = configuredPackage.isNotBlank() && foreground == configuredPackage
```

同时目标窗口通过：

```kotlin
queryWindows()
    .firstOrNull { it.packageName == configuredPackage }
```

查询。

目前 `AccessibleWindow` 没有保存 `isActive` / `isFocused` / layer 信息。

### 影响

在电脑模式多窗口环境中，目标游戏窗口可能仍然存在，但焦点已经切到助手、系统弹窗或其他 App。只要保存的 foregroundPackage 尚未及时刷新，就可能错误通过环境门禁。

后续一旦接入真实点击，这属于高风险误点击来源。

### 修改意见

不要把 AccessibilityEvent.packageName 命名为 foregroundPackage。

建议改为：

- `lastEventPackage`：仅用于诊断。
- 当前目标窗口有效性使用 `AccessibilityWindowInfo.isActive` / `isFocused`、rootInActiveWindow，以及窗口列表层级共同判断。
- `AccessibleWindow` 增加 active / focused / type / layer。
- EnvironmentCheck 必须明确验证“目标窗口存在 + 当前活动/焦点符合策略”。

### 参考代码

```kotlin
data class AccessibleWindow(
    val packageName: String?,
    val bounds: WindowBounds,
    val isActive: Boolean,
    val isFocused: Boolean,
    val type: Int,
    val layer: Int,
)
```

```kotlin
val activePackage = rootInActiveWindow?.packageName?.toString()

val targetWindow = queryWindows()
    .firstOrNull {
        it.packageName == configuredPackage &&
        (it.isActive || it.isFocused)
    }

val targetDetected =
    activePackage == configuredPackage &&
    targetWindow != null
```

如果 ZUI 实测显示 freeform 游戏允许“可见但非 focused”时仍需某些后台观察，可在后续单独定义 `VISIBLE` 和 `ACTIVE`，但真实输入动作前必须再次确认 ACTIVE。

### 建议测试

1. 游戏前台：targetDetected=true。
2. 游戏窗口可见，但点击助手使助手获得焦点：targetDetected=false。
3. 打开系统弹窗覆盖游戏：禁止开始自动化。
4. 游戏窗口关闭但最近事件包仍为游戏：targetDetected=false。
5. 多实例/多窗口时必须选中 active/focused 的目标窗口，而不是任意第一个包名相同的窗口。

---

## 4. BLOCKER-03：WINDOW_CHANGED 只在 RUNNING_PLACEHOLDER 时失败，READY 状态可以带着已变化窗口继续启动

### 问题

`MainViewModel.refresh()` 中：

```kotlin
if (change == WindowChange.CHANGED &&
    stateMachine.state.value == AutomationState.RUNNING_PLACEHOLDER
) {
    stateMachine.dispatch(AutomationEvent.Fail("WINDOW_BOUNDS_CHANGED"))
}
```

而 `runEnvironmentCheck()` 只要 `targetBounds != null` 就会：

```kotlin
DeviceReady
CheckWindow
WindowVerified
```

并没有判断：

- FIRST_SAMPLE 是否已经建立为“标准基线”。
- CHANGED 是否需要重新确认/校准。
- 当前 bounds 是否匹配已确认 viewport profile。

更明显的竞态是：

```text
环境检查 → READY
用户移动/缩放游戏窗口 → CHANGED
当前状态因为不是 RUNNING_PLACEHOLDER，不进入 ERROR
用户点击“开始自动化” → RUNNING_PLACEHOLDER
```

开始前没有再次进行窗口门禁。

### 影响

这违反项目最重要的安全原则之一：

“窗口改变后暂停所有点击，重新校准/识别成功后才允许继续。”

现在虽然 M0 还没有真实点击，但如果不在基础层修正，下一阶段 Action Guard 很容易继承错误前提。

### 修改意见

建立明确的 WindowGate，而不是仅保存 lastBounds。

至少区分：

```text
NO_BASELINE
MATCHED
CHANGED
UNAVAILABLE
```

READY 的必要条件应包括 WindowGate.MATCHED。

BeginAutomation 再做一次同步门禁：

```text
READY
→ verify permission
→ verify active target
→ verify current bounds == accepted baseline/profile
→ RUNNING
```

如果 bounds changed：

```text
READY / RUNNING
→ PAUSED or WINDOW_CHECK
→ revalidate
→ READY
```

不要把普通“窗口发生变化”直接设计成不可恢复 ERROR；它应成为可恢复环境状态。

### 参考代码

```kotlin
fun canEnterRunning(snapshot: EnvironmentSnapshot): Boolean =
    snapshot.accessibilityReady &&
    snapshot.captureSessionReady &&
    snapshot.targetActive &&
    snapshot.windowGate == WindowGate.MATCHED

fun beginAutomation() {
    val snapshot = environmentProbe.snapshot()
    if (!canEnterRunning(snapshot)) {
        stateMachine.dispatch(AutomationEvent.EnvironmentChanged)
        return
    }
    stateMachine.dispatch(AutomationEvent.Begin)
}
```

### 建议测试

1. READY 后移动窗口，再点开始：必须被拒绝。
2. RUNNING 时移动窗口：立即阻止新 Action。
3. 恢复标准 bounds 后重新识别成功，允许恢复。
4. targetBounds 短暂 unavailable 时禁止动作。
5. WindowMonitor 的 CHANGED 状态不能被一次后续 refresh 立刻“冲掉”而失去待处理语义。

---

## 5. BLOCKER-04：STOPPED 不是终态，异步 Fail 可以把 STOPPED 改成 ERROR

### 问题

当前状态机优先处理：

```kotlin
AutomationEvent.Stop -> AutomationState.STOPPED
is AutomationEvent.Fail -> AutomationState.ERROR
```

因此：

```text
STOPPED + Fail(...) → ERROR
```

而测试还显式要求“failure enters error from every state”。

这与架构文档中的“STOP 优先级最高”冲突。

真实系统里很容易发生：

```text
用户点击停止
→ STOPPED
→ MediaProjection / Window callback 稍后返回失败
→ Fail(...)
→ ERROR
```

### 影响

用户明确停止后，应用又显示为错误；更重要的是，未来取消协程和设备回调之间存在竞态时，STOP 不能作为稳定终态。

### 修改意见

STOPPED 必须忽略普通异步失败。

推荐优先级：

```kotlin
if (current == STOPPED && event !is Reset) return null
if (event == Stop) return STOPPED
if (event is Fail) return ERROR
```

是否允许 `STOPPED + Stop → STOPPED` 可以保留幂等。

### 参考代码

```kotlin
internal fun transition(
    current: AutomationState,
    event: AutomationEvent,
): AutomationState? {
    if (current == AutomationState.STOPPED) {
        return when (event) {
            AutomationEvent.Stop -> AutomationState.STOPPED
            AutomationEvent.Reset -> AutomationState.IDLE
            else -> null
        }
    }

    return when (event) {
        AutomationEvent.Stop -> AutomationState.STOPPED
        is AutomationEvent.Fail -> AutomationState.ERROR
        // ...
    }
}
```

### 建议测试

- STOPPED + Fail = rejected，仍 STOPPED。
- STOPPED + delayed WindowChanged = rejected。
- STOPPED + delayed CaptureFailed = rejected。
- STOPPED + Reset = IDLE。
- 任意 RUNNING 状态 + Stop = STOPPED。

---

## 6. TEST/SECURITY-01：Pad Inspector 私有目录为 700，但原始文件未强制 600

### 问题

`pad_inspector.py` 对目录执行：

```python
os.chmod(report_dir, 0o700)
```

但后续 `write_text()` / `write_bytes()` 创建的：

- window.txt
- activity.txt
- display.txt
- screen.png
- window.xml
- report.json

没有显式 chmod。

在常见 umask=022 环境下，这些文件通常可能成为 0644。

它们包含原始系统窗口、包名、UI tree、截图等隐私数据。

### 影响

违反 WORKFLOW 中“敏感目录 700、敏感文件 600”的既定安全基线。

公开 Git 没有泄露，但 Mac 本机同机其他用户可能读取。

### 修改意见

所有 raw artifact 写入后立即 `chmod(0o600)`，或在 capture 范围内使用安全 umask。

建议封装统一函数，防止漏掉新增 artifact。

### 参考代码

```python
def write_private_text(path: Path, value: str) -> None:
    path.write_text(value, encoding="utf-8")
    path.chmod(0o600)

def write_private_bytes(path: Path, value: bytes) -> None:
    path.write_bytes(value)
    path.chmod(0o600)
```

### 建议测试

创建临时目录运行 writer 后：

```python
stat.S_IMODE(path.stat().st_mode) == 0o600
```

对 screenshot、UI XML、window dump、report.json 全部检查。

---

## 7. IMPROVEMENT-01：DailyExecution 的唯一幂等约束有了，但 DAO 测试还没验证“重复业务键”

### 问题

数据库已经正确建立：

```text
gameDayKey + roleId + actionType + targetId
```

唯一索引，这是好设计。

但当前测试只插入一条记录并读取，没有验证“第二个不同 id、相同业务键”会如何处理。

当前 DAO 使用 `@Upsert`，未来如果调用方为重复业务键生成新 id，行为需要明确。

### 影响

后续实现每日免费副本时，若恢复流程对同一业务键生成新的 execution id，可能触发唯一约束异常，而不是安全复用旧记录。

### 修改意见

现在无需重构全部数据库，但应增加一个幂等测试，并在下一阶段明确 Repository 语义：

```text
getOrCreateDailyExecution(gameDayKey, roleId, actionType, targetId)
```

业务层不直接随意 upsert 新 id。

### 建议测试

- 同业务键第二次 getOrCreate 返回原记录。
- SUCCESS 记录不会被新 PENDING 覆盖。
- App 重启后同日同角色同副本仍只存在一条记录。

---

## 8. 已通过部分

以下部分本轮认可：

- Android 工程结构和依赖规模合理，没有过度工程化。
- target package 没有猜测或写死。
- Accessibility 手势能力目前没有被业务 UI 调用。
- Manifest 的 MediaProjection 前台服务类型与权限方向正确。
- Room 没有启用 destructive migration。
- schema 导出已开启。
- Pad Inspector 不打印 ADB endpoint/serial。
- 三种窗口样本和真机安装测试已在 IMPLEMENT 中记录。
- M0 没有越界到副本、OCR、战斗功能。
- GitHub 功能分支与 handoff 流程正确。

---

## 9. 本轮验收状态

```text
REQ-0004 = CHANGES_REQUIRED
DO NOT MERGE TO main
```

Codex 下一轮只修复本 Review，不新增 REQ-0005，不扩展真实游戏自动化。

必须完成 BLOCKER-01 ~ BLOCKER-04。

TEST/SECURITY-01 必须完成。

IMPROVEMENT-01 建议同轮完成；若不完成，必须在 IMPLEMENT-0004 中明确推迟原因和下一任务。

---

## 10. Codex 修订后的交付要求

修订完成后：

1. 继续使用 `feat/req-0004-android-foundation`。
2. 不 squash / 不重写已提交历史。
3. 新增修复 commit。
4. 更新 `codex/reports/IMPLEMENT-0004.md`。
5. 更新 `codex/handoff/LATEST.md`。
6. 重新生成 `LATEST.patch`，比较基线仍使用 `c8b942f3...`。
7. 执行：
   - Gradle unit test
   - connected Android test
   - Pad Inspector tests
   - 安全扫描
   - git diff --check
8. 真机重新验证：
   - 连续帧可读取
   - 停止捕获
   - STOPPED 不被异步失败污染
   - READY 后改变窗口不能启动
   - 游戏窗口可见但非 active 时不能误判为可运行
9. Push GitHub。
10. 交给 ChatGPT进行第二轮复审。
