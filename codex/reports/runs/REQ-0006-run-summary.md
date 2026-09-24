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
