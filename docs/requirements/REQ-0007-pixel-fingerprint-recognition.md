# REQ-0007：固定窗口色点指纹页面识别与交互式标定

状态：PLANNED  
目标分支：`feat/req-0006-single-dungeon-loop`  
优先级：HIGH  
用途：替换 REQ-0006 当前过重的静态页面识别主路径，优先提高真机业务推进速度。  
原则：Business-First MVP。保留必要安全边界，但不继续增加防御层。

---

## 1. 背景

当前 REQ-0006 已具备 MediaProjection、AccessibilityService、OCR、PageDetector、StablePage、ActionGuard、结构化日志等基础能力，但真机业务推进速度受到复杂页面判定和过度后置验证影响。

新的主识别方案改为：

```text
固定游戏窗口位置与大小
→ MediaProjection 获取当前帧
→ 读取少量固定坐标色值
→ Pixel Fingerprint 页面匹配
→ 确认当前页面
→ 执行该页面逻辑
```

不再把“全页 OCR / 复杂 PageDetector”作为静态页面的首选识别方式。

注意：并不是完全取消画面采集。运行时仍需要从 MediaProjection 获取当前帧，但不需要保存整张截图，也不需要默认对整页 OCR。

---

## 2. 核心目标

实现一个 `PixelFingerprintEngine`，在固定窗口、固定 contentViewport 条件下，用每个页面约 20–30 个分散且稳定的色点识别页面。

典型页面：

```text
HOME
AREA_MAP
AREA_PICKER
DUNGEON_LIST
DUNGEON_DETAIL
BATTLE
NETWORK_DISCONNECTED
BAG_FULL
CHARACTER_SELECT
SETTINGS
```

代码内部 pageId、类名、枚举、字段名可以使用英文。

**所有面向用户的页面请求、说明、采样结果、错误提示、下一步操作必须使用中文。**

---

## 3. 人机交互语言规则（强制）

程序内部允许：

```text
HOME
AREA_MAP
AREA_PICKER
DUNGEON_LIST
DUNGEON_DETAIL
BATTLE
NETWORK_DISCONNECTED
BAG_FULL
CHARACTER_SELECT
SETTINGS
```

但每个 pageId 必须同时维护：

```text
pageId
displayNameZh
descriptionZh
enterHintZh
```

例如：

```text
pageId = AREA_PICKER
displayNameZh = 区域选择界面
descriptionZh = 点击“切换区域”后，显示多个区域名称供选择的页面
enterHintZh = 请从区域地图点击“切换区域”
```

Codex 不得只对用户说：

```text
请打开 AREA_PICKER
```

必须说：

```text
请打开【区域选择界面】。
说明：点击“切换区域”后，能看到多个区域名称供选择的页面。
打开后保持不动，并回复“已打开”。
如果游戏中没有这个页面，回复“无此界面”。
如果该页面只是一闪而过、无法稳定停留，回复“瞬时界面”。
```

如果是用户可能不了解的页面，Codex 必须用中文解释：

1. 从哪里进入。
2. 页面大概长什么样。
3. 应看到哪些关键元素。

不要求用户理解内部状态机或英文 pageId。

---

## 4. 交互式页面标定流程（Codex 主导）

页面采样必须由 Codex 主动推进。

用户不需要自己知道程序缺哪些页面。

Codex 维护待采样页面列表，并且**一次只请求一个页面**。

流程：

```text
REQUEST_PAGE
→ Codex 用中文要求用户打开一个具体页面
→ 等待用户回复
→ 根据回复处理
```

用户只需要回复以下三种之一：

```text
已打开
无此界面
瞬时界面
```

### 4.1 用户回复“已打开”

Codex 才可以开始采样。

流程：

```text
CAPTURE
→ 连续采样约 30 帧（约 2–3 秒）
→ ANALYZE
→ 稳定点筛选
→ 跨页面差异筛选
→ 保存 fingerprint
→ VALIDATE
→ 用中文汇报结果
→ 请求下一页面
```

在用户回复“已打开”之前，不允许提前采样并假设页面正确。

### 4.2 用户回复“无此界面”

记录：

```text
pageStatus = NOT_PRESENT
```

不得伪造 fingerprint。

直接进入下一页面请求。

### 4.3 用户回复“瞬时界面”

记录：

```text
pageStatus = TRANSIENT
```

第一阶段不强行建立固定色点指纹。

后续可通过以下方法处理：

```text
前后已知页面状态变化
局部 Template
特定 ROI
超时窗口
```

不要为了瞬时页面继续增加复杂识别层。

---

## 5. 初始采样页面顺序

第一轮建议 Codex 按以下顺序逐个请求：

```text
1. 游戏主页
   pageId = HOME

2. 区域地图界面
   pageId = AREA_MAP

3. 区域选择界面
   pageId = AREA_PICKER

4. 地下城列表界面
   pageId = DUNGEON_LIST

5. 地下城详情界面
   pageId = DUNGEON_DETAIL

6. 战斗界面
   pageId = BATTLE

7. 服务器断开连接提示界面
   pageId = NETWORK_DISCONNECTED

8. 背包已满提示界面
   pageId = BAG_FULL

9. 角色选择界面
   pageId = CHARACTER_SELECT

10. 设置界面
    pageId = SETTINGS
```

该列表只是初始候选。

Codex 必须根据真实游戏页面情况接受：

```text
PASS
NEED_RESAMPLE
NOT_PRESENT
TRANSIENT
```

不得假设所有页面一定存在。

---

## 6. 固定窗口与坐标系

色点识别成立的前提是：

```text
游戏窗口位置固定
游戏窗口尺寸固定
contentViewport 固定
缩放比例固定
```

每个 fingerprint 必须关联：

```text
referenceWidth
referenceHeight
viewportProfileId
```

第一版优先支持单一已确认窗口 Profile，不要求立即做任意分辨率自适应。

如果当前窗口与 fingerprint Profile 明显不匹配：

```text
不要继续使用旧坐标
→ 要求恢复标准窗口
```

不要因为这个需求继续扩充新的复杂 PRECHECK 架构。

---

## 7. 页面色点采样算法

### 7.1 连续帧采样

每个静态页面采样：

```text
约 30 帧
持续约 2–3 秒
```

不以单帧作为 fingerprint 来源。

### 7.2 稳定点

对候选位置统计多个采样帧中的 RGB 波动。

优先保留：

```text
多帧颜色变化小
非动画区域
非角色经过区域
非技能特效区域
非滚动内容区域
```

自动排除颜色变化明显的坐标。

### 7.3 空间分散

候选点不能全部集中在一个角落。

最终点应尽量覆盖：

```text
左上
右上
中心
左下
右下
关键固定 UI 区域
```

避免因为局部遮挡导致整个页面误判。

### 7.4 独特性

“稳定”不等于“有识别价值”。

当已有其他页面 fingerprint 后，必须进行跨页面比较。

优先保留：

```text
当前页面中稳定
+
其他页面同位置颜色差异明显
```

纯黑背景、通用边框等在所有页面都相同的点，应降低优先级。

### 7.5 第一版建议参数

初始值允许后续根据真机调整：

```text
最终 fingerprint 点数：20–30
候选稳定点：30–50
RGB tolerance：约 ±12
页面命中阈值：约 0.85
```

不要把参数硬编码到不可调整。

建议支持：

```text
per-point tolerance
page requiredMatchRate
```

---

## 8. Fingerprint 数据结构

建议结构：

```json
{
  "pageId": "HOME",
  "displayNameZh": "游戏主页",
  "referenceWidth": 1280,
  "referenceHeight": 720,
  "viewportProfileId": "default_tablet_split",
  "requiredMatchRate": 0.85,
  "points": [
    {
      "x": 124,
      "y": 76,
      "r": 212,
      "g": 174,
      "b": 93,
      "tolerance": 12
    }
  ]
}
```

实际 referenceWidth / referenceHeight 必须来自当前真实标准窗口，不允许照抄示例数字。

Fingerprint 默认存放在 App 私有目录。

开发阶段可以导出脱敏后的结构化 fingerprint 配置，但不得提交原始游戏截图、设备信息、账号信息或其他敏感数据。

---

## 9. 实时页面识别

主识别流程：

```text
MediaProjection Frame
→ 根据固定坐标读取 fingerprint points
→ RGB tolerance 比较
→ 计算各页面命中率
→ 取得分最高页面
→ 达到阈值则返回该 PageId
→ 否则 UNKNOWN
```

示例调试输出：

```text
游戏主页 / HOME                94%
区域地图界面 / AREA_MAP        19%
地下城列表界面 / DUNGEON_LIST  12%
战斗界面 / BATTLE               8%

当前页面：游戏主页
```

面向用户的主要内容必须中文。

---

## 10. 页面识别与 OCR 的职责分离

### 10.1 Pixel Fingerprint

主要负责：

```text
“当前是什么页面？”
```

例如：

```text
HOME
AREA_MAP
AREA_PICKER
DUNGEON_LIST
DUNGEON_DETAIL
BATTLE
NETWORK_DISCONNECTED
BAG_FULL
```

### 10.2 ROI OCR

只负责动态数据，例如：

```text
免费(current/total)
战斗 progress current/total
当前区域名称（必要时）
具体副本名称（必要时）
```

只 OCR 对应小 ROI，不默认整页 OCR。

### 10.3 Template Matching

仅在固定图标、按钮或色点不足以区分时使用。

例如：

```text
自动战斗图标
特定免费角标
特殊弹窗图标
```

### 10.4 现有 PageDetector / Full OCR

暂时保留。

定位为：

```text
fallback
debug
新页面分析
```

本阶段不要删除旧代码，也不要继续增强其复杂度。

---

## 11. 开发模式 UI

助手新增一个仅用于开发/标定的页面：

```text
页面指纹标定
```

至少显示：

```text
当前请求页面（中文）
内部 pageId
中文页面说明
采样状态
采样帧数
候选稳定点数量
最终 fingerprint 点数
实时页面匹配结果
各页面得分
```

用户不需要手工输入坐标或 RGB。

Codex/程序负责自动采样和筛选。

---

## 12. 每页采样完成后的中文汇报

每完成一个页面，Codex 必须用中文向用户报告：

```text
页面：游戏主页
内部ID：HOME
采样帧数：30
候选稳定点：42
最终指纹点：24
自身稳定命中率：96%
与其他已采样页面的区分情况：通过
状态：PASS
```

如果质量不足：

```text
状态：NEED_RESAMPLE

原因：
该页面存在较多动画区域，当前稳定点不足。

请继续停留在【游戏主页】，我重新采样一次。
```

不要悄悄接受低质量 fingerprint。

---

## 13. 运行时状态机原则

当 Pixel Fingerprint 确认页面后，业务逻辑采用简单方式推进：

```text
识别页面
→ 执行该页面逻辑
→ 等待短时间
→ 再识别
→ 必要时有限重试
```

例如：

```text
HOME
→ 点副本入口

AREA_MAP
→ 点地下城 / 切区域

DUNGEON_LIST
→ 选择候选免费副本

DUNGEON_DETAIL
→ ROI OCR 免费(current/total)
→ current > 0 才允许“前往”

BATTLE
→ 判断自动战斗
→ ROI OCR progress current/total
→ 等待完成

NETWORK_DISCONNECTED
→ 执行受控重连逻辑

BAG_FULL
→ 执行受控自动出售逻辑
```

普通导航识别失败优先：

```text
等待
→ 重识别
→ 有限重试
```

不要因为第一帧失败立即终止整个 session。

---

## 14. 必须保留的安全底线

Business-First 不代表取消安全控制。

以下仍为硬限制：

1. 必须确认目标是游戏窗口。
2. 当前页面必须是已知页面，UNKNOWN 不允许盲点。
3. 点击目标必须来自明确的固定坐标配置、Template、OCR/Accessibility 证据之一。
4. 进入副本前必须明确读到：
   ```text
   免费(current/total)
   current > 0
   ```
5. PURCHASE / 钻石 / 付费次数 / 挑战券 / 未知付费确认继续 `FORBIDDEN_AUTO`。
6. 不允许自动操作账号登录、支付、充值相关页面。

除此之外，不再因为普通导航的轻微识别波动继续增加新的防御层。

---

## 15. 明确非目标

本需求第一阶段不做：

```text
多角色
多副本批量轮询
Boss 技能策略
云端 AI
JEV
ChatGPT API 运行时识图
大模型页面判断
大规模 ActionGuard 重构
新的复杂 PRECHECK
任意窗口尺寸自适应
瞬时页面强制 fingerprint
```

---

## 16. 开发阶段

### Phase A：页面采样器

先完成：

```text
固定窗口 Profile
连续帧采样
稳定点分析
色点筛选
fingerprint 保存
实时分数显示
```

本阶段不要自动执行副本业务点击。

### Phase B：Codex 主导真机采样

Codex 按中文交互式流程一次请求一个页面。

用户回复：

```text
已打开
无此界面
瞬时界面
```

逐步建立页面库。

### Phase C：识别稳定性验证

对已采样页面逐个测试实时识别。

重点记录：

```text
正确页面得分
第二名页面得分
UNKNOWN 比例
误识别页面
```

### Phase D：重新接回 REQ-0006

确认静态页面识别可靠后，再接回：

```text
HOME
→ AREA_MAP
→ AREA_PICKER
→ DUNGEON_LIST
→ DUNGEON_DETAIL
→ BATTLE
→ HOME
→ SUCCESS
```

现有 `first-free-dungeon` 占位 ID 可继续临时保留，真实多副本去重不属于本需求第一阶段。

---

## 17. 验收标准

本需求第一阶段验收不要求完成副本 SUCCESS。

先验收“页面指纹系统”本身。

至少满足：

1. 用户不需要手工填写坐标或 RGB。
2. Codex 能主动用中文逐页要求用户打开需要的页面。
3. 用户回复“已打开”后才执行采样。
4. 支持“无此界面”和“瞬时界面”。
5. 每个静态页面可连续采样多帧并自动生成 fingerprint。
6. fingerprint 点空间分散，且经过跨页面独特性筛选。
7. 能实时显示所有已采样页面的匹配分数。
8. 当前页面可以由 Pixel Fingerprint 作为主路径识别。
9. OCR 不再作为静态页面主分类器，只用于动态 ROI。
10. 不需要云端 AI / 大模型参与运行时页面判断。
11. 原始截图、设备敏感信息不得提交公开 GitHub。
12. 旧 PageDetector/OCR 代码保留作 fallback/debug，不要求删除。

完成后提交：

```text
IMPLEMENT-0007.md
页面采样结果摘要
已采集 pageId 列表
NOT_PRESENT / TRANSIENT 列表
每页 fingerprint 点数
实时识别验证结果
剩余问题
```

---

## 18. Codex 执行原则

Codex 开始本需求后：

- 先 `git pull`。
- 阅读本文件。
- 不要重新设计一套复杂架构。
- 不要先扩业务流程。
- 先把采样器做到可用。
- 之后必须停下来，由 Codex 一页一页向用户请求真机页面。
- 面向用户的请求和解释全部使用中文。
- 内部代码可使用英文。
- 页面不知道是否存在时，必须问用户，不得猜。
- 页面只能一闪而过时接受“瞬时界面”，不要强行采样。
- 第一次采样完成前，不继续增加新的 Guard / PRECHECK 层。

