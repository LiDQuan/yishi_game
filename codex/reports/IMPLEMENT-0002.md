# IMPLEMENT-0002：公开仓库安全改造

## 对应需求编号

REQ-0002

## 本次实现目标

为仓库公开建立敏感数据隔离、配置样例、安全扫描、历史检查和协作政策，并完成提交前验证。

## 修改文件

- `.gitignore`
- `docs/WORKFLOW.md`
- `codex/handoff/LATEST.md`

## 新增文件

- `.env.example`
- `device.env.example`
- `docs/requirements/REQ-0002.md`
- `tools/security/check_public_repo.py`
- `codex/reports/IMPLEMENT-0002.md`
- `codex/handoff/LATEST.patch`

## 核心实现说明

- 强化 Git 忽略规则，覆盖本地 env、凭据、签名文件、ADB 数据、私有运行数据、日志、crash dump 和 Python/Android 生成物。
- 创建只含占位值或空值的公开配置样例。
- 新增无第三方依赖的安全扫描器，检查工作区、Git 暂存区、历史文件和历史提交信息。
- 扫描命中时只输出规则名、文件和行号，不回显匹配值。
- 在协作协议中加入公开仓库与 Mac 本地数据边界、Keychain 优先级、日志/截图规则和提交前检查命令。
- 创建本地 `~/.config/yishijieyongzhe/private/` 目录树并设置目录权限为 `700`；已有顶层 env 文件统一设置为 `600`。

## 关键技术决策

- 扫描器仅使用 Python 标准库，避免为提交门禁增加依赖。
- 同时扫描工作区和暂存区，防止“暂存了 Secret、随后只清理工作区”的遗漏。
- 完整历史按唯一 Git blob 扫描，并额外扫描提交信息；历史不自动重写。
- 二进制截图无法可靠做文本脱敏检测，继续要求人工复核。
- 安全实现提交与报告归档提交分离，以便报告记录稳定的实现 commit hash。

## 构建结果

未执行 Android 构建：仓库当前没有 Android/Gradle 应用工程。本次 Python 工具已通过语法编译检查。

## 测试结果

- `python3 tools/security/check_public_repo.py --self-test`：通过。
- `python3 tools/security/check_public_repo.py --history`：通过；实现提交后扫描 28 个当前/暂存文本候选、34 个历史文本对象，0 个二进制或超大对象被跳过。
- 合成假凭据阻断测试：通过；退出码为 1，只报告位置与规则，未输出匹配值。
- `.gitignore` 关键路径测试：通过；私有数据被忽略，公开样例与脱敏目录仍可跟踪。
- Git whitespace 检查：源文件通过；`LATEST.patch` 以逐字匹配原始 `git diff` 的方式单独验证。

## 真机测试结果

未执行：本任务不包含应用代码、APK 或 ADB 交互。

## 已知问题

- 文本扫描器不能判断二进制截图是否已脱敏，必须人工复核。
- 既有 Git 提交元数据包含作者邮箱。它不是 Token 或认证凭据，但可能属于用户希望隐藏的个人信息；本次未在没有用户确认的情况下重写历史。

## 未完成内容

- 是否将既有提交作者邮箱改为公开安全地址，需要用户在仓库公开前确认。

## 后续建议

- 在 CI 或 pre-commit 中执行 `python3 tools/security/check_public_repo.py --history`。
- 引入统一配置读取模块时实现 Environment Variables → macOS Keychain → 本地 env → 安全默认值的顺序。
- 提交任何截图前继续执行人工隐私检查。

## Git commit hash

`3fe0b734699111857d3ef74acf97898242a3845a`
