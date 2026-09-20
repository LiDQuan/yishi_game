# ChatGPT 复审交接

## 当前任务编号

REQ-0005

## 当前版本

Android M1 视觉与 Action Guard 基础版；待 ChatGPT Review。

## 本次修改摘要

完成 FrameStore 缓冲复用和轻量 metadata、ViewportMapper、ROI 中文 OCR、TemplateMatcher、版本化页面定义、PageDetector、StablePage、Vision Debug、Action Guard Dry-Run 和私有样本工具。真机识别 3 类用户手动到达的真实页面；未执行任何游戏输入。

## 完整 commit hash

`560108d020d1429bda72a70c71de09412a6dac64`

该 hash 为待复审实现提交；本交接、IMPLEMENT 和 patch 由后续元数据提交归档。

## git diff --stat

```text
29 files changed, 1127 insertions(+), 18 deletions(-)
```

比较范围：`85bc798..560108d020d1429bda72a70c71de09412a6dac64`。完整差异见 `codex/handoff/LATEST.patch`。

## 关键代码改动说明

- UI 不再观察整屏 RGBA，只观察 FrameMetadata；后台 VisionWorker 限频处理最新帧。
- 3 类页面每类要求 2 个 OCR 证据，低置信度、近似候选和 OCR 失败不会强制归类。
- StablePage 用 N/M 窗口抑制瞬时误判，viewport 版本变化立即失效。
- Action Guard 仅生成 Dry-Run 计划或 DENY，对敏感操作、窗口变化、失焦、采集失效和缺证据采用保守拒绝。
- Vision Debug 显示 FPS、当前/P95 耗时、OCR/PageDetector 耗时、Detection、StablePage、免费状态和 Guard 结果。

## 测试结果

- JVM 单元测试：24 项通过，0 失败。
- connected Android tests：3 项通过，0 失败；私有真实页面测试再单独运行 1 项并通过。
- 真实页面：SETTINGS、CHARACTER_SELECT、DUNGEON_LIST 全部匹配。当前无“免费(1/1)”真实样本，正确返回 UNKNOWN。
- Vision Sampler 1 项、Pad Inspector 5 项全部通过。
- 公开仓库安全扫描和 `git diff --check` 通过。

## 性能基线

- Pad：2944×1840。
- 观察值：Capture 约 3.5 FPS；Vision 约 1.6 FPS；单轮约 22–198 ms。
- 私有三页 OCR 管线：1.754 秒，平均约 585 ms/页。
- 5 分钟 PSS：280130 → 320180 KiB，范围 167543–339739 KiB；非单调增长，未见无界泄漏或明显 UI 卡顿。

## 错误信息

- Android SDK XML 工具版本提示不阻塞构建。
- 连接测试会卸载被测 APK，导致后续单独私有 fixture 测试需重装 APK 并重新放入私有样本；已重跑通过。

## 尚未解决的问题

- 真实模板集隐私审核仍为 PENDING，未入公开仓库；当前真机三页验证使用 OCR 双证据。
- 没有显示“免费(1/1)”的真实副本详情样本，因此不做 AVAILABLE 真机声明。
- 内存基线波动较大，建议 Review 后继续长时间采样。

## 希望 ChatGPT 重点审查的内容

- 双 OCR 证据、候选差值和 StablePage N/M 是否足够保守。
- 本机私有 viewport/fixture 的保密边界及窗口变化失效策略。
- Action Guard 全局门禁、敏感证据门禁和永久禁止操作的拒绝顺序。
- 5 分钟 PSS 波动和整屏 snapshot 拷贝在后续版本是否需要进一步改为 ROI 拷贝。
