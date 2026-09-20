# ChatGPT 第二轮复审交接

## 当前任务编号

REQ-0005

## 当前版本

第一轮 REVIEW-0005 BLOCKER/REQUIRED 修复版；等待第二轮 Review。

## 本次修改摘要

修复 StablePage 跨窗口/采集生命周期残留和最新帧回退问题；分离 windowBounds/contentViewport；增加页面新鲜度门禁；打通真实 DUNGEON_LIST OCR anchor 到 ALLOW_DRY_RUN 的只读链路；完成 FIXED 模板 required/negative 最小闭环和可配置语义 OCR 入口。没有新增 REQ，也没有执行真实游戏输入。

## 完整 commit hash

`25cd436880a4bba57393004fa84571782d5d7456`

该 hash 为本轮实现提交；本交接文档与 patch 由其后的元数据提交归档。

## git diff --stat

```text
18 files changed, 303 insertions(+), 57 deletions(-)
```

比较范围：`5e7e66e063d0037d7d4e8bd0fdff0c24769f08e4..25cd436880a4bba57393004fa84571782d5d7456`。完整差异见 `codex/handoff/LATEST.patch`。

## 关键代码改动说明

- 窗口异常、采集状态、目标包及 viewport 接受均触发视觉历史立即失效。
- 最新帧不是相同页面 MATCHED 时，不再向 Guard 暴露旧 StablePage。
- contentViewport 使用显式本地 profile，真实 fixture 坐标移出公开源码。
- StablePage 暴露来自真实 VisionEvidence 的 anchor rect；Guard 增加 version/age 校验。
- 真机私有 DUNGEON_LIST fixture 可形成 ALLOW_DRY_RUN，负例仍保守 DENY；无任何 gesture。
- TemplateDefinition 完整消费 ROI、threshold、negative、FIXED policy；免费次数新增显式可配置入口，默认仍 UNKNOWN。

## 测试结果

- JVM：28 项通过，0 失败，0 跳过。
- Android 真机公开测试通过；最终 APK 上另行执行私有 fixture 1 项通过（1.512 秒），包含三页识别和真实 anchor 正/负 Dry-Run 门禁。
- Pad Inspector / Vision Sampler：6 项通过。
- 构建、Android test 编译、`git diff --check` 均通过。
- 公开仓库安全扫描（含历史）通过。

## 错误信息

- 首次构建环境未暴露 JDK/SDK 路径；使用本机已安装 JDK 17 与 Android SDK 的显式路径后通过。
- Android SDK XML 版本提示不阻塞。
- Gradle 连接测试重装会清除应用私有 fixture；最终 APK 重装后重新注入本机私有样本并直接执行测试，结果通过。
- 安全扫描最初把 Review 示例中的采集会话字段误判为 secret assignment；已将语言类型加入 placeholder 白名单，扫描器自测及完整扫描通过。

## 尚未解决的问题

- 真实模板仍为本机私有 PENDING 资产，未提交。
- 免费次数 ROI 未经真实样本确认，保持 UNKNOWN。
- LIMITED_SCALE 未实现，当前只支持 FIXED。

## 希望 ChatGPT 重点审查的内容

- BLOCKER-01/02 的立即失效与 actionable StablePage 规则。
- contentViewport profile 在未确认、窗口变化和重新接受时的保守行为。
- 真实 VisionEvidence → anchor rect → ALLOW_DRY_RUN，以及失焦/resize/缺 anchor 拒绝顺序。
- 模板 required/negative 闭环和语义 OCR 默认 UNKNOWN 是否满足 M1 边界。
