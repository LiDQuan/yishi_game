# REQ-0007 Phase A 实现记录

状态：Phase A 代码完成，等待真机逐页采样。当前工作仍在 `feat/req-0006-single-dungeon-loop`，没有接入副本业务点击。

采样入口为助手中的「页面指纹标定」。已复用 MediaProjection 帧和已确认的 contentViewport；每次采集 30 个不同帧的候选 RGB 数值，不保存整帧。采样器剔除波动超过 12 的位置，从 5×4 个空间区域各取稳定且尽量区别于已保存页面的点。指纹关联真实窗口与 viewport 尺寸、位置，保存在 App 私有目录 `files/pixel-fingerprints/`。窗口 Profile 不匹配时拒绝采样及评分。标定页显示中文页面说明、帧数、稳定点数、最终点数、实时得分及页面结论，并可记录「无此界面」「瞬时界面」。

本阶段没有对现有 REQ-0006 PageDetector、OCR、ActionGuard 或业务流程做切换；待 Phase B 逐页建立和验证指纹库后再评估主识别路径切换。

已采集 pageId：无。NOT_PRESENT：无。TRANSIENT：无。每页点数与实时识别验证结果：待真机采样，不能预填。当前尚无 REQ-0007 真机页面识别结论，也没有 REQ-0006 SUCCESS session。

验证：`assembleDebug`、`testDebugUnitTest`、`git diff --check`、公开仓库安全扫描通过。下一步只请求游戏主页，待用户明确回复「已打开」后开始采样。
