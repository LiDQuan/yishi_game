# ChatGPT ↔ Codex 协作流程

## 主仓库

GitHub `LiDQuan/yishi_game` 是当前唯一开发主仓库，默认分支为 `main`。

历史 Gitee 仓库只作为历史来源或可选镜像，不再作为开发流程中的权威来源。若 GitHub 与其他镜像内容冲突，以 GitHub `main` 为准。

## 职责边界

### ChatGPT

- 需求设计与变更管理
- 系统架构
- 修改建议
- Code Review
- Bug 分析
- 验收标准
- 直接读取 GitHub 的需求、实现报告、提交和 diff
- 提交文档类变更

### Codex

- 实际代码实现
- 本地构建
- ADB 真机测试
- 修改文件
- Git commit
- Push GitHub
- 输出 IMPLEMENT 报告和复审交接
- 在开始任务前同步 `origin/main`

### GitHub

- 项目唯一权威主仓库
- 保存源码、需求、架构、Review、实施报告和脱敏后的开发信息
- 代码类大改优先采用分支 + Pull Request；纯文档小改允许直接提交 `main`

## 标准任务流程

1. ChatGPT 创建或完善需求文档，并明确验收标准。
2. Codex 拉取 GitHub 最新 `main`，读取对应 REQ 和架构文档。
3. Codex 按需求实现、构建和测试；涉及 Android 交互时执行 ADB 真机测试。
4. Codex 提交代码并推送 GitHub，同时生成 IMPLEMENT 报告和 `codex/handoff/LATEST.md`。
5. ChatGPT 直接读取 GitHub 上的提交、diff、IMPLEMENT 和 LATEST 进行复审。
6. ChatGPT 将审查结果写入 `docs/reviews/REVIEW-XXXX.md`。
7. 未通过项沿用原任务编号修正；需求范围发生实质变化时创建新任务编号。

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
- 自动化测试结果
- ADB / 真机测试结果
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

复杂代码改动还应生成 `codex/handoff/LATEST.patch`：

```bash
git diff <上一已验收commit>..<当前commit>
```

ChatGPT 已可直接读取 GitHub commit diff，因此 patch 是备用交接材料，不再是强制的人工传输渠道。

## Git 规则

- 开发前执行同步，确认基于最新 `origin/main`。
- 不删除或覆盖已有有效文件；发现冲突先停止并说明。
- 纯文档小改可直接更新 `main`。
- Android 代码、状态机、识别逻辑、数据迁移和其他高风险修改优先通过功能分支提交。
- 不允许 force push `main`，除非用户明确批准历史重写。
- 提交信息应包含任务编号或清晰的 Conventional Commit 语义。

## Public Repository Security Policy

### 数据边界

GitHub 公开仓库允许保存：

- 源码、开发文档、需求和 Review
- 已脱敏的实施报告、交接文件和问题摘要
- 不含真实值的配置模板
- 已确认不含个人、账号、设备或网络标识的攻略与技能数据库

Mac 本地 `~/.config/yishijieyongzhe/` 保存：

- Token、密码、Cookie、Session、OAuth 和其他 Secret
- 设备凭据、ADB 地址、配对码、序列号和网络信息
- 完整日志、原始 logcat、crash dump 和错误截图
- APK 签名文件、keystore 及其密码

敏感目录和文件权限应分别设置为 `700` 和 `600`。长期凭据优先使用 macOS Keychain。配置读取顺序：

1. Environment Variables
2. macOS Keychain
3. `~/.config/yishijieyongzhe/*.env`
4. 安全默认值或仅报告缺失项名称

任何 Secret 均不得写入源码、文档、报告、交接文件、提交信息或普通日志。

### 日志、诊断和截图

- 完整日志、原始 logcat、crash dump 和错误截图默认只保存在本地私有目录。
- 公开仓库仅保存错误编号、状态机节点、非敏感错误信息和已脱敏诊断字段。
- 截图提交前必须人工检查账号、昵称、通知、网络信息、Token、聊天内容、二维码和个人数据并完成脱敏。
- 二进制截图无法由文本扫描器可靠验证，必须保留人工复核环节。
- 若未来需要自动远程诊断，优先使用独立私有诊断仓库或本地 Mac 中转，不在公开仓库自动上传原始现场数据。

### 提交前安全检查

每次提交和生成公开交接材料前执行：

```bash
python3 tools/security/check_public_repo.py --history
```

扫描失败时必须阻止提交，只报告规则名、文件和行号，不得回显匹配内容。若真实 Secret 曾进入 Git 历史，应立即作废并重新生成；历史重写必须在用户明确确认后进行。
