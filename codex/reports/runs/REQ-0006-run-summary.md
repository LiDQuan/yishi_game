# 2026-09-25 Diagnostic Run（新增）

本轮安装含诊断日志的 APK 后，Pad 游戏左、助手右；辅助功能、MediaProjection 与窗口布局均已恢复并经应用 PRECHECK 检查。游戏在启动前再次弹出“网络错误 / 已与服务器断开连接”。本轮私有 JSONL 会话末尾为 `PRECHECK_CHECKS FAILED / GAME_NOT_AT_HOME`，稳定页 `NETWORK_DISCONNECTED`；九项检查中前八项 PASS、HOME FAIL，最终 `SESSION_END FAILED / PRECHECK_GAME_NOT_AT_HOME`。业务阶段停在 **PRECHECK**，真实游戏业务 tap 为 0。未进入 AREA_MAP、AREA_PICKER 或副本；免费次数、战斗进度、自动战斗状态均无本轮数据，**没有 SUCCESS session**。

本轮代码只增加私有日志里的 OCR 文本及置信度、页面候选置信度、postcondition 阻断原因；没有因服务器异常绕过 PRECHECK，也没有在错误页继续坐标动作。`AREA_PICKER → AREA_MAP` 新版后置验证尚未取得真机样本。原始日志和截图仍只在 App/本机私有目录，公开仓库仅此脱敏摘要。

---

# REQ-0006 真机运行脱敏摘要 — 2026-09-25 续测

- Pad 经 ADB 授权连接；游戏左、助手右的窗口布局已在截图中核验。两次已保存的私有 JSONL 会话均执行 PRECHECK，且检查项 9/9 通过；原始 sessionId、截图、JSONL 留在本机私有目录。
- 会话 A：`PRECHECK_READY → WAIT_TARGET_ACTIVE → FAILED / ENVIRONMENT_CHANGED`；游戏未及时成为活动窗口，真实游戏业务 tap 为 0。
- 会话 B：`PRECHECK_READY → RUNNING → DailyExecution RUNNING → HOME → OPEN_AREA_MAP (Guard ALLOW，Accessibility 接受) → AREA_MAP 在游戏中可见 → FAILED / ACTION_OPEN_AREA_MAP_BLOCKED_OR_UNVERIFIED`。视觉日志显示地图证据与背景战斗计数同时出现，导致 `Ambiguous`；本次代码已调整判定，但尚无该修复的真机回归结果。受控真实业务 tap 为 1 次。
- 随后游戏反复出现“网络错误 / 已与服务器断开连接”，弹窗仅有“确定”。用户曾手动返回 HOME，但弹窗重现。新版 APK 已安装；重新安装造成辅助功能绑定与 MediaProjection 授权失效，未在新版完成 PRECHECK 或启动新业务会话。没有在断线弹窗上进行 ADB 游戏点击。用户已授权后续 Guard 控制的“确定”恢复动作，但运行中尚未验证。
- 执行区域：会话 B 尚停在当前地图，未进入区域选择；历史受控会话曾切换目标区域，但后置条件失败。执行副本：未选定；免费次数：`UNKNOWN`；progress：未读取；自动战斗：未判定；自动返回 HOME：否；DailyExecution 最终 SUCCESS：否；同日 SKIPPED：否。断线发生；背包满未观察到。购买和付费确认 0 次。
- 当前结论：**FAILED / 未完成 REQ-0006 验收**。必须待游戏稳定联网、重新获得两项系统授权后，从 HOME 重新 PRECHECK，逐步验证地图、区域、免费次数、战斗、HOME 与同日 SKIP，不得沿用旧证据。

---

# REQ-0006 真机运行脱敏摘要

## 2026-09-24 Precheck 续测

- 真机环境：游戏左、助手右；游戏可见且停留 HOME；有线 ADB 连通。
- 启动结果：`PRECHECK_FAILED / PRECHECK_ACCESSIBILITY_UNAVAILABLE`。设备测试重新安装助手后 Android 辅助功能授权失效；用户尚未重新打开系统开关。
- 最后稳定页面：HOME（由屏幕观察，**非**助手视觉流水线确认）；最后动作：助手内启动测试按钮；真实游戏业务 tap：0。
- `REQ-0006_PRECHECK READY`：未观察到。截图不可用、游戏/助手不可见、布局错误、非 HOME、viewport 未确认等失败路径有 JVM 用例，但本轮尚无真机证据。
- 副本状态：未进入地图或地下城；免费次数、自动战斗、进度均未采集；真实 SUCCESS：否。原始截图和设备信息仅留本机，未入仓库。

状态：**FAILED / 未完成**。没有成功运行样本，不能作为 REQ-0006 验收证据。

## 中断前受控会话

- sessionId：原始 App 私有日志在设备测试重装后丢失，无法核验；不编造 ID。
- 总运行时间：未保留可靠起止时间，无法核验。
- 执行区域：从当前区域切换至“亡灵之地”（视觉截图已观察）。
- 执行副本：未进入列表；无副本名称。
- 状态轨迹：`HOME → AREA_MAP → AREA_PICKER → AREA_MAP(视觉可见，但页面后置条件超时) → FAILED`。
- 真实游戏 gesture：已确认 3 次，均由当时构建的 Action Guard 放行：打开地图、打开区域选择、选择区域。第三次 tap 使地图切换，但动作后置条件未在时限内确认，错误码 `ACTION_SELECT_AREA_BLOCKED_OR_UNVERIFIED`。
- 免费次数：未读取；`UNKNOWN`。战斗进度：未进入战斗，无数据。自动战斗：未检测，无操作。
- 购买/钻石确认：0 次；未执行购买动作。

## 恢复后的设备状态

- sessionId：无新业务会话。游戏显示“网络错误 / 已与服务器断开连接”。
- 状态轨迹：设备连通 → 游戏断线对话框 → 未启动真实游戏流程。
- 真实游戏 gesture：0 次。没有尝试通过游戏确认按钮掩盖服务器状态。
- 最终结果：**BLOCKED / GAME_SERVER_DISCONNECTED**；并非副本通关失败判定。

## 尚缺的成功样本

T06 角标、T07 `免费(n/m)`、`前往`、原生自动战斗状态、逐次 `progress current/total`、`total/total`、自动返回 T01、DailyExecution SUCCESS 和同日跳过均未获真机证据。服务器恢复后须以新 session 采集并更新本摘要。
