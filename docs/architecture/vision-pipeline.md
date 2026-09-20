# 视觉识别管线设计

对应需求：REQ-0005

## 1. 目标

视觉系统的职责是：

```text
Frame
→ Viewport
→ ROI
→ Signals
→ Page Candidate
→ Stable Page
```

它只负责“看见什么”，不负责“点什么”。

业务动作必须经过 Action Guard。

## 2. 分层

### Capture

负责提供帧。

### FrameStore

保存最新可消费帧，隔离 Capture 与 Vision。

### Viewport

确定游戏内容区域和 normalized 坐标映射。

### Signal Detectors

- Template。
- OCR。
- 后续可增加 color/shape/feature。

### PageDetector

将多个 signal 合成页面候选。

### StablePageTracker

过滤瞬态和切页噪声。

## 3. Frame 不进入 UI 主状态

错误方式：

```text
StateFlow<ScreenFrame(ByteArray)>
→ Compose collect
→ 每帧重组
```

正确方向：

```text
Capture
→ FrameStore
→ Vision Worker
→ VisionResult(StateFlow)
→ Compose
```

UI 只接收 VisionResult 和轻量 metadata。

## 4. ROI

ROI 一律相对 contentViewport：

```text
0.0 ~ 1.0
```

这允许窗口改变位置后仍映射正确。

窗口大小发生变化时：

- 当前 viewport profile 失效。
- Vision 停止产生“可执行”结果。
- 需要重新确认 viewport。
- 旧 StablePage 标记 stale。

## 5. VisionEvidence

统一 evidence：

```kotlin
sealed interface VisionEvidence {
    val id: String
    val confidence: Float
    val rect: Rect
}
```

实现：

- TemplateEvidence。
- OcrEvidence。
- 后续其他。

PageDetector 不直接依赖 OpenCV/ML Kit具体 API。

## 6. TemplateMatcher 接口

```kotlin
interface TemplateMatcher {
    suspend fun match(
        frame: FrameSnapshot,
        template: TemplateDefinition,
        viewport: Rect,
    ): TemplateMatch?
}
```

必须运行在后台 Dispatcher，不阻塞 main thread。

## 7. OCR 接口

```kotlin
interface OcrEngine {
    suspend fun recognize(
        frame: FrameSnapshot,
        roi: Rect,
    ): List<OcrEvidence>
}
```

OCR engine 可以使用本地 Android-compatible 引擎。

优先离线，避免依赖网络。

## 8. PageDefinition

页面不是单 anchor。

推荐：

```text
required signals
optional signals
negative signals
threshold
minimumMargin
```

高风险页面增加多个独立信号。

## 9. Scoring

初版允许简单加权：

```text
required all pass
optional weighted average
negative veto or penalty
```

不要在 M1 过度设计机器学习分类器。

必须可解释：

```text
为什么判定 SETTINGS = 0.94
```

例如：

```text
settings.header template 0.97
settings.close icon 0.93
ocr "设置" 0.91
negative purchase dialog absent
```

## 10. StablePageTracker

单帧识别不等于业务页面。

跟踪：

- 最近结果。
- pageId。
- confidence。
- viewport version。
- window bounds version。

任何以下事件清空 stable state：

- WindowGate changed。
- viewport profile changed。
- target package changed。
- capture restarted。
- template set changed。

## 11. UNKNOWN 优先

错误成本不对称。

识别错页面并点击的成本远高于：

```text
暂时 UNKNOWN 多等 300ms
```

因此 PageDetector 的设计目标不是最大命中率，而是：

> **高置信命中 + 保守 UNKNOWN。**

## 12. 调试模式

Debug Screen 应允许：

- 查看当前 ROI。
- 查看模板命中框。
- 查看 OCR ROI 和文本。
- 查看 Page Candidates。
- 手工冻结最新一帧。
- 导出脱敏后的 debug summary。

不默认保存完整截图。

## 13. Template Set

目录建议：

```text
templates/
└── vision/
    ├── schema/
    ├── public-fixtures/
    └── manifests/
```

私有模板：

```text
~/.config/yishijieyongzhe/private/vision/templates/
```

配置层可以将两者合并为 runtime TemplateRegistry。

## 14. 性能策略

按优先级：

1. ROI。
2. Vision 降帧。
3. 避免重复 OCR。
4. 页面已稳定时降低检测频率。
5. 只检测与当前流程有关的候选页面。
6. buffer 复用。
7. 必要时降采样。

不要一开始就对所有模板跑全屏匹配。

## 15. 页面上下文缩小候选集

未来状态机可以告诉 Vision：

```text
当前从 MAIN 打开 SETTINGS
预计下一页只可能：
SETTINGS
POPUP
UNKNOWN
```

PageDetector 可以只加载相关定义。

M1 可以先保留全 registry，但接口必须允许 candidate filter。

## 16. 输出给业务层

业务层只消费：

```text
StablePage
VisionFacts
```

例如：

```kotlin
data class VisionFacts(
    val stablePage: StablePage?,
    val freeAttemptState: FreeAttemptState,
    val observedAt: Long,
    val viewportVersion: Long,
)
```

业务层不直接解析 OCR string。

## 17. FreeAttemptState

```text
AVAILABLE
EXHAUSTED
UNKNOWN
```

视觉层只从证据生成状态。

未见文字：

```text
UNKNOWN
```

而不是 EXHAUSTED。

## 18. 测试原则

需要三层测试：

### Pure JVM

- ROI。
- scoring。
- stable tracker。
- OCR normalization。
- guard facts。

### Android Integration

- OCR engine。
- template engine。
- FrameStore。

### Pad

- 实际自由窗口。
- 实际游戏 UI。
- 失焦。
- resize。
- 10 秒稳定识别。
