# Codex 开工入口

当前主仓库：GitHub `LiDQuan/yishi_game`  
当前应实施任务：`REQ-0005`

## 当前基线

REQ-0004 已通过三轮 Review 并合入 `main`。

M0 已具备：

- Android / Compose 基础。
- AccessibilityService。
- MediaProjection 持续帧。
- WindowGate。
- 安全启动/恢复握手。
- Room。
- Pad Inspector。
- 真机开发链路。

不要重做 M0。

## 开工前阅读顺序

1. `docs/WORKFLOW.md`
2. `docs/requirements/REQ-0003.md`
3. `docs/requirements/REQ-0005.md`
4. `docs/architecture/system-design.md`
5. `docs/architecture/vision-pipeline.md`
6. `docs/architecture/action-guard.md`
7. `docs/architecture/state-machine.md`
8. `docs/architecture/data-model.md`
9. `docs/reviews/REVIEW-0004.md`

## 当前任务

执行：

`docs/requirements/REQ-0005.md`

建议功能分支：

`feat/req-0005-vision-foundation`

## 本轮核心目标

不是“开始刷副本”。

而是建立：

```text
Frame
→ Viewport
→ ROI
→ Template/OCR
→ PageDetector
→ StablePage
→ ActionGuard Dry-Run
```

并在 Pad 上用真实游戏页面证明识别链路有效。

## 硬性禁止

本轮禁止：

- 点击游戏设置。
- 自动切角色。
- 自动进副本。
- 点击“前往”。
- 点击“免费挑战”。
- 战斗操作。
- 任何付费/购买行为。
- Vision 模块直接调用 AccessibilityController。

Action Guard 只能返回：

```text
ALLOW_DRY_RUN
DENY
```

不得执行 gesture。

## 优先修复 M0 技术债

在开始大量视觉识别前先处理：

- 原始整屏 RGBA 大对象不要推动 Compose 每帧刷新。
- 建立 FrameStore / snapshot。
- 降低 Vision FPS。
- ROI 优先。
- 记录性能基线。

## 真实游戏样本

可用无线 ADB + Pad 手动页面切换进行验证。

真实完整截图默认保存：

`~/.config/yishijieyongzhe/private/vision/`

不得自动提交 GitHub。

若需要把小 ROI template 加入公开仓库：

1. 裁剪到最小必要区域。
2. 人工检查隐私。
3. 确认不含用户身份/账号/聊天/通知。
4. 再提交。

## 未确认事实

不要猜：

- “免费次数用尽”页面长什么样。
- 任何按钮精确坐标。
- 未采集页面的视觉模板。
- 游戏 UI 更新后的文本格式。

没有样本：

```text
UNKNOWN
```

不是猜测。

## 开发流程

1. 同步 `origin/main`。
2. 确认包含 REQ-0004 merge。
3. 创建 `feat/req-0005-vision-foundation`。
4. 先做 Frame / viewport / test。
5. 再做 template / OCR。
6. 再做 PageDetector / StablePage。
7. 最后做 Action Guard dry-run。
8. 真机只识别，不操作游戏。
9. 执行完整测试和安全扫描。
10. 生成 `codex/reports/IMPLEMENT-0005.md`。
11. 更新 `codex/handoff/LATEST.md`。
12. Push GitHub。
13. 交给 ChatGPT Review。

## 完成后必须提供

- 实现 commit hash。
- 分支。
- 构建结果。
- unit tests。
- connected Android tests。
- Vision 测试。
- Pad 3 类真实页面识别结果。
- 性能基线。
- 安全扫描。
- 未完成/UNKNOWN 样本。
- IMPLEMENT-0005。
- LATEST.md。

ChatGPT 将直接读取 GitHub 进行复审。
