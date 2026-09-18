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

## Public Repository Security Policy

### 数据边界

Gitee 公开仓库允许保存：

- 源码、开发文档、需求和 Review
- 已脱敏的实施报告、交接文件和问题摘要
- 不含真实值的配置模板
- 已确认不含个人、账号、设备或网络标识的攻略与技能数据库

Mac 本地 `~/.config/yishijieyongzhe/` 保存：

- Token、密码、Cookie、Session、OAuth 和其他 Secret
- 设备凭据、ADB 地址、配对码、序列号和网络信息
- 完整日志、原始 logcat、crash dump 和错误截图
- APK 签名文件、keystore 及其密码

敏感目录和文件权限应分别设置为 `700` 和 `600`。长期保存的 PAT、API Token 和签名密码优先使用 macOS Keychain；需要读取配置时按以下顺序处理：

1. Environment Variables
2. macOS Keychain
3. `~/.config/yishijieyongzhe/*.env`
4. 安全默认值或仅报告缺失的配置项名称

任何 Secret 均不得写入源码、文档、报告、交接文件、提交信息或普通日志，也不得在错误信息中回显。业务代码应通过统一配置读取层访问敏感信息。

### 日志、诊断和截图

- 完整日志、原始 logcat、crash dump 和错误截图默认只保存在本地私有目录。
- 公开仓库仅保存错误编号、状态机节点、非敏感错误信息和已脱敏诊断字段。
- 截图提交前必须人工检查账号、昵称、通知、网络信息、Token、聊天内容、二维码和个人数据并完成脱敏。
- 二进制截图无法由文本扫描器可靠验证，必须保留人工复核环节。
- 仓库公开前必须检查 Git 作者姓名和邮箱等提交元数据；如需隐藏，应先配置公开安全的身份，再经用户确认后重写历史。

### 提交前安全检查

每次提交和生成公开交接材料前执行：

```bash
python3 tools/security/check_public_repo.py --history
```

扫描失败时必须阻止提交，只报告规则名、文件和行号，不得回显匹配内容。若真实 Secret 曾进入 Git 历史，应立即作废并重新生成；历史重写必须在用户明确确认后进行。
