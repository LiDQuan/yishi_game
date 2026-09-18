# ChatGPT ↔ Codex 协作流程

## 职责边界

### ChatGPT

- 需求设计
- 系统架构
- 修改建议
- Code Review
- Bug 分析
- 验收标准

### Codex

- 实际代码实现
- 本地构建
- ADB 真机测试
- 修改文件
- Git commit
- Push Gitee
- 输出实施报告

### Gitee

Gitee 是项目唯一主仓库，用于保存源码、需求、Review、实施报告和诊断数据。

## 标准任务流程

1. ChatGPT 创建或完善需求文档，并明确验收标准。
2. Codex 按需求实现、构建和测试；涉及 Android 交互时执行 ADB 真机测试。
3. Codex 提交代码并推送 Gitee，同时生成 IMPLEMENT 报告和 `codex/handoff/LATEST.md`。
4. ChatGPT 根据需求、提交差异、测试证据和 LATEST 进行复审，并输出 REVIEW 文档。
5. 未通过项沿用原任务编号修正；需求范围发生实质变化时创建新任务编号。

## 任务编号与文件位置

任务编号从 `REQ-0001` 起连续递增：

- 需求文档：`docs/requirements/REQ-XXXX.md`
- Codex 实现报告：`codex/reports/IMPLEMENT-XXXX.md`
- ChatGPT Review：`docs/reviews/REVIEW-XXXX.md`

`XXXX` 必须与对应需求编号的四位数字一致。提交信息、报告和交接文件应引用同一任务编号。

## IMPLEMENT 报告要求

每个完成的任务必须生成对应 IMPLEMENT 报告，至少包含：

- 对应需求编号
- 本次实现目标
- 修改文件
- 新增文件
- 核心实现说明
- 关键技术决策
- 构建结果
- 测试结果
- 真机测试结果
- 已知问题
- 未完成内容
- 后续建议
- Git commit hash

无法执行的构建或测试必须明确写明原因，不能以“通过”代替。

## ChatGPT 复审交接

每次任务完成后更新 `codex/handoff/LATEST.md`，至少包含：

- 当前任务编号
- 当前版本
- 本次修改摘要
- 完整 commit hash
- `git diff --stat`
- 关键代码改动说明
- 测试结果
- 错误信息
- 尚未解决的问题
- 希望 ChatGPT 重点审查的内容

复杂代码改动还应生成 `codex/handoff/LATEST.patch`，内容为：

```bash
git diff <上一已验收commit>..<当前commit>
```

不存在已验收 commit 时，使用当前提交的合适父提交；首个提交可使用 Git 空树作为比较基线。

## Git 与安全规则

- 开发前同步 Gitee；完成后检查差异、提交并推送。
- 不删除或覆盖已有有效文件；发现冲突时先停止并说明。
- Token、密码、Gitee PAT、签名文件、`local.properties`、环境变量文件及其他凭据禁止提交。
- 提交前检查 `git status`、暂存区差异和敏感信息。
- 诊断数据提交前必须确认不含账号、设备标识、凭据和其他隐私信息。

