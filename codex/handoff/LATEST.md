# ChatGPT 复审交接

## 当前任务编号

REQ-0004（第二轮复审 R2 修订）

## 当前版本

Android M0 基础设施 R2 修订版；待复审代码提交：`a8c57d61566380a0e5fa0153c351db28d39dfa52`。

## 本次修改摘要

仅修复 `R2-BLOCKER-01` 并完成 `R2-TEST-GATE-01`：加入显式恢复和二阶段安全握手，修复 SurfaceView 窗口识别，并在最终代码上完成自动测试、公开仓库安全扫描和 Pad GUI 回归。未新增 REQ，未实现 OCR、副本、战斗或任何游戏输入。

## 完整 commit hash

`a8c57d61566380a0e5fa0153c351db28d39dfa52`

该 hash 为待复审代码提交；本交接、IMPLEMENT 更新和 patch 由后续元数据提交归档。

## git diff --stat

```text
7 files changed, 274 insertions(+), 54 deletions(-)
```

比较范围：`c3ffb15da44dd8e5df2e6a11ce110cd01358bfb8..a8c57d61566380a0e5fa0153c351db28d39dfa52`。这是相对上一轮已复审实现的 R2 专项差异；完整内容见 `codex/handoff/LATEST.patch`。

## 关键代码改动说明

- `targetVisible` 负责配置和环境门禁，`targetActive` 只负责第二阶段运行许可。
- “准备启动”从 READY 进入 `WAIT_TARGET_ACTIVE`；目标窗口激活且采集/窗口门禁仍安全时才进入 `RUNNING_PLACEHOLDER`。
- 运行失焦进入 PAUSED；游戏重新获得焦点不会自动恢复。用户必须点击“显式恢复并重新握手”，然后再次切回游戏。
- 窗口变化、窗口不可用、采集失效、辅助服务失效统一要求完整环境复查，不能走普通恢复。
- Cocos/SurfaceView 根节点没有包名时，通过 accessibility event 的 windowId 补充窗口身份；焦点判断仍使用实时窗口 active/focused 属性。
- UI 只把辅助服务真实连接视为“已启用”，消除 ZUI 残留配置造成的假阳性。

## 测试结果

- Gradle 构建与 JVM 单元测试：通过，`BUILD SUCCESSFUL`。
- `connectedDebugAndroidTest`：2 项通过，0 失败、0 跳过。
- Pad Inspector：5 项通过。
- 安全扫描 self-test 与历史扫描：通过；88 个当前/索引文本候选、123 个历史文本对象，跳过 2 个二进制或超大对象。
- `git diff --check`：通过。

## 真机测试结果

- Pad 无线 ADB 在线，user 0 只有一个助手包，辅助服务真实绑定。
- GUI 完整通过：环境检查 → READY → 准备启动 → WAIT_TARGET_ACTIVE → 游戏聚焦 → RUNNING_PLACEHOLDER → 助手聚焦 → PAUSED。
- PAUSED 后游戏单纯重新聚焦仍保持 PAUSED；显式恢复后重新进入 WAIT_TARGET_ACTIVE，再聚焦游戏才恢复运行占位态。
- 屏幕采集持续产出非空帧；只共享助手自身。没有点击游戏内容区，也没有 OCR、副本或战斗行为。
- 连接设备测试完成后已重装同一 APK、恢复辅助服务并重新保存本机配置；敏感的实际包名、设备地址、截图和诊断原文均未入库。

## 错误信息

- 首次最终构建因终端未设置 JDK/SDK 路径而未启动；指定已安装的 OpenJDK 17 和 Android SDK 后构建成功。这不是代码失败。
- Android SDK XML 版本提示未阻塞构建。
- ZUI 在 APK 被测试任务卸载/重装后会清空或关闭辅助功能设置，已在最终唯一实例上恢复并核验真实绑定。

## 尚未解决的问题

- R2-IMPROVEMENT-01（高分辨率 RGBA 缓冲分配）按 Review 明确不阻塞 REQ-0004，留待进入视觉识别前优化。
- M0 之外的 OCR、模板识别、副本和战斗功能仍未开始。

## 希望 ChatGPT 重点审查的内容

- `WAIT_TARGET_ACTIVE` 的二阶段门禁是否满足“助手可操作、游戏获焦后才允许运行”的要求。
- PAUSED 的 `EXPLICIT_CONFIRMATION` 与 `ENVIRONMENT_CHECK` 两类恢复要求是否隔离充分。
- SurfaceView 窗口 ID 映射是否保持“事件仅标识窗口、实时窗口属性判焦点”的安全边界。
- 30 秒等待超时及辅助服务真实连接门禁是否足够保守。
