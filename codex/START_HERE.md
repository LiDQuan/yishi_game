# Codex 开工入口

当前主仓库：GitHub `LiDQuan/yishi_game`  
当前任务：`REQ-0006`

## 当前阶段

- REQ-0004：Android M0，已完成。
- REQ-0005：视觉识别 + Action Guard Dry-Run，已完成并合入 main。
- **现在正式进入业务开发。**

## 开工顺序

1. 同步最新 `main`。
2. 阅读 `docs/requirements/REQ-0006.md`。
3. 阅读 `docs/architecture/action-guard.md`。
4. 只在需要时回看 REQ-0005 / REVIEW-0005。

建议分支：

`feat/req-0006-single-dungeon-loop`

## 核心任务

直接跑通：

```text
当前角色
→ 目标副本
→ 免费状态
→ 真实进入
→ 原生自动战斗
→ 胜负
→ 奖励/返回
→ DailyExecution
```

## 本轮允许真实点击

允许通过 Action Executor 执行经过 Guard 的真实 tap。

但禁止：

- 购买/钻石/额外次数。
- 多角色。
- 多副本。
- 手动技能脚本。
- 日常任务。
- 继续大规模扩建基础设施。

## 工作方法

缺页面样本时直接用无线 ADB 和真机采集，不要停下来设计一堆未来框架。

优先级：

```text
先跑通业务
> 再处理明显 bug
> 再做扩展
```

## 完成后

更新：

- `codex/reports/IMPLEMENT-0006.md`
- `codex/handoff/LATEST.md`
- `codex/handoff/LATEST.patch`

Push GitHub，然后让 ChatGPT Review。
