# 异世界勇者自动化助手

面向 Android 平板（首要目标设备：联想小新 Pad Pro 12.7）的个人游戏自动化与诊断工具。

项目目标不是制作简单坐标连点器，而是建立一套可维护的自动化系统：

- 运行环境与游戏窗口标准化
- 多角色轮询
- 副本每日免费次数保护
- 自动日常任务
- 原生自动施法 / 脚本施法双模式
- 图像识别 + OCR + 攻略规则引擎
- 技能、Boss、攻略数据采集
- Room 本地状态与断点续跑
- 错误现场、脱敏诊断与 GitHub 协作闭环

## 当前状态

项目处于 MVP 基础设施阶段。

已完成：

- ChatGPT ↔ Codex 协作规范
- 公开仓库安全基线
- GitHub 主仓库迁移
- 无线 ADB 已在目标 Pad 与 Mac 之间打通

下一开发任务：

- [REQ-0004：Android MVP 基础设施与 Pad 环境采集](docs/requirements/REQ-0004.md)

## 文档入口

- [总体产品需求 REQ-0003](docs/requirements/REQ-0003.md)
- [第一开发任务 REQ-0004](docs/requirements/REQ-0004.md)
- [系统架构](docs/architecture/system-design.md)
- [自动化状态机](docs/architecture/state-machine.md)
- [数据模型](docs/architecture/data-model.md)
- [协作流程](docs/WORKFLOW.md)
- [Codex 开工入口](codex/START_HERE.md)

## 安全边界

本项目只通过 Android 系统提供的辅助功能、屏幕采集、图像识别和本地状态机进行自动化；不修改游戏客户端、不注入进程、不绕过反作弊机制。

公开仓库不得保存 Token、密码、ADB 私有信息、原始日志、未脱敏截图、签名材料或其他个人敏感信息。详见 [WORKFLOW.md](docs/WORKFLOW.md)。

## 仓库

主仓库：`LiDQuan/yishi_game`

默认分支：`main`
