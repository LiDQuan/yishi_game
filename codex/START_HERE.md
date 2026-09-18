# Codex 开工入口

当前主仓库：GitHub `LiDQuan/yishi_game`  
当前应实施任务：`REQ-0004`

## 开工前阅读顺序

1. `docs/WORKFLOW.md`
2. `docs/requirements/REQ-0003.md`
3. `docs/architecture/system-design.md`
4. `docs/architecture/state-machine.md`
5. `docs/architecture/data-model.md`
6. `docs/requirements/REQ-0004.md`

REQ-0001 / REQ-0002 是历史基础设施任务，不是当前 Android 业务实现目标。

## 当前任务

执行：

`docs/requirements/REQ-0004.md`

不要直接开始完整刷副本。

当前目标是先证明：

- Android 工程能构建。
- APK 能安装目标 Pad。
- Accessibility / MediaProjection 基础链路可用。
- Room 与状态机骨架正确。
- ADB Pad Inspector 能采集真实设备环境。
- 能检测电脑模式下游戏自由窗口变化。
- 得到“自动恢复窗口尺寸是否可行”的实测结论。

## 已知现场条件

- 用户使用联想小新 Pad Pro 12.7。
- Pad 与 Mac 已成功通过无线 ADB 配对并显示为 `device`。
- 游戏运行在联想电脑模式的自由窗口中，可人工缩放。
- 已确认游戏角色切换大体路径：
  设置 → 返回角色选择 → 选择角色 → 进入游戏。
- 已观察副本详情出现“免费(1/1)”状态。
- 游戏在满足条件时存在原生自动释放技能能力。
- 部分场景需要后续脚本施法。

不要从这些描述推断未确认的包名、精确坐标、窗口尺寸或按钮模板。

## Git 工作方式

1. 同步 `origin/main`。
2. 检查工作树。
3. 建议为 REQ-0004 创建功能分支，例如：
   `feat/req-0004-android-foundation`
4. 实现。
5. 执行自动测试、构建、安全扫描。
6. 通过无线 ADB 真机测试。
7. 生成 `codex/reports/IMPLEMENT-0004.md`。
8. 更新 `codex/handoff/LATEST.md`。
9. 复杂改动生成 `LATEST.patch`。
10. Push GitHub。
11. 报告 commit / branch / PR（若创建）。

## 遇到不确定事实时

优先：

- 用 ADB 实测。
- 从设备系统信息读取。
- 从当前仓库文档读取。
- 将无法确认的内容做成配置。

不要：

- 猜坐标。
- 猜游戏包名。
- 猜 ZUI 自由窗口内部接口。
- 伪造真机测试通过。

## 安全要求

开始提交前执行：

```bash
python3 tools/security/check_public_repo.py --history
```

任何原始截图、ADB endpoint、设备 serial、真实账号信息、Token 都留在本地私有目录。

## 任务完成后的 ChatGPT 复审

ChatGPT 已经能够直接读取 GitHub。

因此完成后只需确保 GitHub 上存在：

- 实现 commit。
- `IMPLEMENT-0004.md`。
- `LATEST.md`。
- 必要的 patch 或 PR。

用户无需手工复制大量日志给 ChatGPT。
