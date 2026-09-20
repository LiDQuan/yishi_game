# IMPLEMENT-0005：第一轮 Review 修复

## 对应需求编号

REQ-0005

## 本次实现目标

仅修复 `REVIEW-0005` 第一轮列出的 4 个 BLOCKER 和 3 个 REQUIRED 项；不新增需求，不接入任何真实游戏点击、滑动、返回、角色切换、副本或战斗。

## 修改文件

- 修正 StablePage 生命周期、最新帧判定和 Action Guard 新鲜度门禁。
- 将 windowBounds 与 contentViewport 分层，并增加本地 profile schema 示例。
- 打通真实 OCR anchor 到 Action Guard 的只读 Dry-Run 链路。
- 将 TemplateDefinition 的 ROI、阈值、negative 和 FIXED scale policy 接入生产视觉引擎。
- 增加可配置语义 OCR 信号；默认未配置免费次数 ROI，因此保持 UNKNOWN。
- 修正公开仓库安全扫描器对 Kotlin 类型声明的误报。

## 新增文件

- `config/content-viewport.example.json`

## 核心实现说明

- WindowGate 进入 CHANGED/UNAVAILABLE、采集状态变化、目标包变化及重新接受 viewport 时立即清空视觉历史；窗口版本同步递增。
- 最新 detection 必须是同页 MATCHED 才能产出 actionable StablePage；UNKNOWN、AMBIGUOUS 或不同页面立即得到 null。
- StablePage 携带 anchor rect；Action Guard 校验 viewport version 和最大年龄，分别拒绝 PAGE_VIEWPORT_MISMATCH 与 PAGE_STALE。
- 生产视觉只读取与当前 windowBounds 精确匹配的已确认 contentViewport profile；未确认时返回 UNKNOWN，Guard 返回 VIEWPORT_INVALID。
- DUNGEON_LIST 的已采样 `dungeon.anchor` 作为 NORMAL 级只读计划来源。UI和真机测试只展示 would-tap rect，从未调用手势执行器。
- 模板链路现为 Definition → content ROI → FIXED matcher → threshold → TemplateEvidence → PageDetector required/negative。LIMITED_SCALE 未实现，也不再宣称支持。

## 关键技术决策

- 延续最小修复：不引入新的视觉依赖或动作执行层。
- 当前 Pad 实测 profile 中 Accessibility windowBounds 与游戏 contentViewport 相同，但数据模型仍明确分层；真实数值只存本机私有 fixture metadata，公开仓库仅留虚构示例。
- 免费次数信号必须显式配置 ROI；无配置或无真实证据一律 UNKNOWN。

## 构建结果

- `assembleDebug`：通过。
- Android test 源码编译：通过。
- 存在 SDK XML 工具版本提示，不影响构建和测试。

## 自动化测试结果

- JVM 单元测试：28 项通过，0 失败，0 跳过。
- Pad Inspector 与 Vision Sampler：6 项通过。
- `git diff --check`：通过。
- 覆盖最新帧 UNKNOWN/AMBIGUOUS/不同页失效、不同 contentViewport 映射、模板正负证据、页面过期和 viewport mismatch。

## ADB / 真机测试结果

- 无线 ADB 目标 Pad 在线。
- `connectedDebugAndroidTest`：公开设备测试通过；Gradle 重装会清除私有 fixture，因此随后在同一最终 APK 上单独运行私有测试，1 项通过（1.512 秒）。
- 私有 fixture 测试从每张截图的私有 JSON metadata 读取 windowBounds/contentViewport，不再在源码硬编码真实坐标。
- 私有测试中 SETTINGS、CHARACTER_SELECT、DUNGEON_LIST 三页识别通过。
- DUNGEON_LIST 真实 OCR anchor → Action Guard 返回 ALLOW_DRY_RUN；同场景失焦、窗口变化、缺 anchor 分别正确 DENY。
- 全程 0 gesture；未向游戏执行点击、滑动或返回。

## 安全扫描

- 扫描器自测通过。
- 当前文件、Git 索引及完整可达历史扫描通过：127 个当前/索引文本文件、180 个历史文本 blob，2 个二进制或超大对象跳过。
- 未提交私有截图、fixture metadata、设备地址、包名、账号数据或凭据。

## 已知问题

- contentViewport 当前由环境检查显式接受；若设备装饰区发生变化，必须重新确认 profile，系统不会猜 inset。
- 真实模板仍保留在本机且隐私审核为 PENDING；公开仓库不包含真实模板。
- SDK XML 版本提示仍存在。

## 未完成内容

- 未实现 LIMITED_SCALE。
- 未配置免费次数真实 ROI，FreeAttemptState 继续为 UNKNOWN。
- 未实现任何真实游戏操作、副本自动化或战斗功能。

## 后续建议

- 等待 ChatGPT 第二轮 Review，重点复审视觉失效时序、contentViewport profile 和真实 Dry-Run 正负门禁。
- Review 通过前不扩展动作能力。

## Git commit hash

`25cd436880a4bba57393004fa84571782d5d7456`
