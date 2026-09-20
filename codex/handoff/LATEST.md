# ChatGPT 复审交接

## 当前任务编号

REQ-0004（REVIEW-0004 修订）

## 当前版本

Android M0 基础设施修订版；待复审实现提交：`c3ffb15da44dd8e5df2e6a11ce110cd01358bfb8`。

## 本次修改摘要

仅修复 REVIEW-0004 的 4 个 BLOCKER、TEST/SECURITY-01 和 DailyExecution 幂等建议项。没有新增 REQ、没有实现副本/OCR/模板/坐标/游戏点击。

## 完整 commit hash

`c3ffb15da44dd8e5df2e6a11ce110cd01358bfb8`

该 hash 为待复审代码提交；本文件、IMPLEMENT 更新和 patch 由后续元数据提交归档。

## git diff --stat

```text
46 files changed, 6493 insertions(+), 467 deletions(-)
```

比较范围：`c8b942f3..c3ffb15da44dd8e5df2e6a11ce110cd01358bfb8`。完整差异见 `codex/handoff/LATEST.patch`。

## 关键代码改动说明

- 捕获：`MediaProjectionCaptureService` 保留一个授权对应的持续 VirtualDisplay/ImageReader 会话；`latestFrame` 提供已复制的 RGBA、stride、timestamp。停止或系统回收转入 `Stopped`。
- 前台：不再把最近 AccessibilityEvent 当作前台；目标必须是 rootInActiveWindow 与同包 active/focused 窗口同时匹配。
- 窗口：`WindowGate` 维护明确接受基线，变化状态不会被后续 refresh 冲掉；READY/RUNNING 在变化时分别回到 WINDOW_CHECK/PAUSED，开始前再校验。
- 停止：STOPPED 屏蔽异步 Fail，避免停止后的 capture/window 回调改写为 ERROR。
- 数据与安全：DailyExecution 改为幂等 get-or-create；Pad Inspector 原始 artifact 一律强制 owner-only `0600`。

## 测试结果

- Gradle 单元测试：通过。
- Room 仪器测试：本轮修订中已成功执行 2 项；最后一次复跑因无线 ADB 无 ready 设备而无法启动，不能视为最终通过。
- Pad Inspector：5 项测试通过，包含所有 raw artifact 的 `0600` 权限断言。
- 安全扫描：通过；未提交私有 Pad 报告、屏幕内容、设备地址或目标包名。
- Git whitespace：通过（排除作为精确 diff 归档的 `LATEST.patch` 与标准 CRLF 的 `gradlew.bat`）。

## 真机验证

- 持续会话：助手自身画面更新下达到 33 帧，未重复弹授权。
- 停止：停止后采集与自动化均显示 STOPPED，未见异步失败污染；服务已确认终止。
- 多窗口门禁：游戏窗口可见、助手为 active 时，目标显示“未识别”，M0 开始按钮禁用。
- 帧字节复制：服务实际使用已单测的 `copyScreenFrame`，在关闭 Image 前复制 Buffer；本次新 UI 的字节数展示未能在最后一次 ADB 断连前完成真机读取。

## 错误信息

- Android SDK XML 版本提示未阻塞构建。
- 无线 ADB mDNS 可发现，但连接服务当前不可达；最后一次 `connectedDebugAndroidTest` 报告 “No connected devices”。
- 系统共享选择器在多窗口列表中切换到无关应用后，已立即 force-stop 助手并确认投屏服务停止；未继续采集。

## 尚未解决的问题

- 需要 Pad 恢复 ready 后，再次运行最终 `connectedDebugAndroidTest`，并真机查看最新帧字节数；这是外部连接阻塞，不是代码验收通过。
- M0 之外的游戏识别、自动化和副本功能仍明确未实现。

## 希望 ChatGPT 重点审查的内容

- 持续 MediaProjection 会话的资源回收、帧节流和 `Stopped` 语义。
- active/focused 多窗口门禁及 READY → WINDOW_CHECK / RUNNING → PAUSED 的保守性。
- STOPPED 对投屏/窗口延迟回调的隔离。
- DailyExecution 的 get-or-create 语义是否满足后续恢复流程。
