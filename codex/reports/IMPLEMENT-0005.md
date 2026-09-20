# IMPLEMENT-0005：视觉与 Action Guard 基础设施

## 对应需求编号

REQ-0005

## 本次实现目标

建立 M1 视觉识别基础：可复用帧存储、Viewport 坐标映射、ROI OCR、模板匹配、多证据页面识别、StablePage 和 Action Guard Dry-Run。本轮不向游戏执行点击、滑动、返回、角色切换、副本进入或战斗操作。

## 修改文件

- 升级 Android M1 版本并加入离线 ML Kit 中文 OCR。
- 改造 MediaProjection 与 MainViewModel，只向 UI 发布轻量 FrameMetadata，视觉工作器在后台取帧。
- 增加 Vision Debug 只读页面和 Action Guard Dry-Run 评估入口。

## 新增文件

- `capture/FrameStore.kt`
- `viewport/ViewportMapper.kt`
- `vision/` 下 OCR、TemplateMatcher、PageDetector、StablePage、VisionWorker 及版本化配置。
- `action/ActionGuard.kt`
- 相应 JVM/Android 测试。
- `tools/vision-sampler/` 私有样本裁剪工具。
- `templates/vision/manifests/` 脱敏示例 manifest。

## 核心实现说明

- `LatestFrameStore` 复用底层 RGBA 缓冲；Compose 只观察帧号、尺寸和 FPS，避免整屏字节流触发 UI 重组。
- Viewport 和 ROI 用归一化坐标定义；窗口版本变化会清空 StablePage。
- ML Kit 仅识别配置的小 ROI，不做整屏 OCR。OCR 失败和未出现“免费(1/1)”均返回 `UNKNOWN`。
- 页面定义要求标题+独立锚点两个证据，并保留 `UNKNOWN` / `AMBIGUOUS` / `MATCHED` 显式结果。
- Action Guard 只返回 `ALLOW_DRY_RUN` 或 `DENY`；不引用无障碍控制器，不存在手势执行路径。敏感操作缺证据及窗口/失焦/采集门禁异常时均 DENY。
- 调试页显示 Capture/Vision FPS、单轮耗时、P95、OCR/PageDetector 耗时、页面、StablePage、免费状态和 Guard 结果。

## 关键技术决策

- 优先使用 Android/ML Kit 和 Kotlin 标准能力，未引入自动化框架或图像大库。
- 真实截图和 ROI 模板保存在本机私有目录（0700/0600）；公开仓库只提交无用户数据的配置示例。
- 目标失焦时允许保留只读识别调试，但 Action Guard 仍拒绝任何动作。

## 构建与测试结果

- `testDebugUnitTest assembleDebug`：通过，24 个 JVM 测试、0 失败、0 跳过。
- `connectedDebugAndroidTest`：3 项通过、0 失败；私有真实页面测试再单独运行 1 项，1.754 秒通过。
- FrameStore、ViewportMapper、TemplateMatcher、OCR、PageDetector/StablePage、ActionGuard 单元测试：通过。
- Vision Sampler：1 项通过；Pad Inspector：5 项通过；`git diff --check`：通过。
- Android SDK XML 版本提示仍存在，不阻塞构建。

## 真机测试结果

- Pad 分辨率：2944×1840；目标 viewport 由 Accessibility 实时读取，未在生产代码猜测设备尺寸。
- 用户手动到达的 3 类真实页面全部识别通过：`SETTINGS`、`CHARACTER_SELECT`、`DUNGEON_LIST`。
- 真实页面 OCR 管线端到端平均约 585 ms/页（3 页总计 1.754 秒，包含图片转换、ROI OCR 与 PageDetector）；最终 Debug 版已提供分项耗时和最近 100 次 P95。
- 实时采集观察到约 3.5 Capture FPS、1.6 Vision FPS，单轮视觉约 22–198 ms。
- 5 分钟 PSS 采样 11 次：起始 280130 KiB，结束 320180 KiB，最小 167543 KiB，最大 339739 KiB；存在 GC 波动，未呈现单调无界增长，UI 无明显卡顿。
- Action Guard 真机 Dry-Run 返回 `DENY / AUTOMATION_NOT_RUNNING`，没有执行动作。
- 本轮只操作系统授权页、系统应用选择器和助手 UI；3 个目标页面均由用户手动到达，没有点击游戏内容区。

## 安全扫描

- 公开仓库扫描通过：113 个当前/索引文本文件、0 历史文本 blob 问题，跳过 1 个二进制或超大对象。
- 未发现 Pad IP/端口、配对码、真实包名、截图、签名文件、PAT/密钥或 `local.properties`。
- Vision/Action 代码未发现点击、滑动、全局返回或 AccessibilityController 调用。

## 已知问题

- 当前 3 类页面以 OCR 双证据为主；TemplateMatcher 已实现和测试，但真实模板隐私复审状态为 `PENDING`，未提交公开仓库。
- 5 分钟内存基线波动较大，后续应在更长运行和多窗口尺寸下继续观测。
- Pad 系统会在覆盖安装/连接测试后关闭辅助服务，真机 GUI 回归前需重新确认绑定。

## 未完成内容

- 未实现副本自动化、战斗、角色操作或任何真实输入。
- 此次副本页面没有显示“免费(1/1)”，因此真机状态正确保持 `UNKNOWN`。

## 后续建议

- 先由 ChatGPT 复审 ROI、双证据页面定义、内存基线和 Action Guard 拒绝顺序。
- Review 通过前不扩展副本、战斗或真实输入能力。

## Git commit hash

`560108d020d1429bda72a70c71de09412a6dac64`
