# 澄清追问的人工选择机制（HumanGate）

> 变更范围：`interview-agent-java`（后端） + `interview-agent-web`（前端）
> 涉及阶段：`jd_analysis`（JD 分析） + `resume_match`（简历匹配）

## 1. 背景问题

`jd_analysis` / `resume_match` 两个阶段都会让 LLM 对"当前输入信息是否足够支撑后续分析"做一次
**语义自评**（区别于 `WebSocketHandler.validateInterviewInput` 的规则层长度校验）：

- JD 只有标题、没有任何技术要求/职责描述；
- 简历长度达标但内容无效——PDF/扫描件解析出乱码、简历只有姓名联系方式没有任何工作经历/项目/技能。

自评不足时，流程会暂停在原地，通过 `requestClarification` 向用户追问，等待补充后重新分析，
最多循环 2 轮，轮次耗尽仍不足则带着现有信息继续（不无限等待）。

**改造前的问题**：用户对追问的回复是一段自由文本，后端需要再调一次 LLM
（`JDAnalyzer.isNoMoreInfo`）去判断这段话语义上是"确实没有更多信息了"还是"给出了实质补充"。
这带来两个缺点：多一次 LLM 调用（延迟+成本）、存在误判风险（自然语言表达千变万化）。

## 2. 方案取舍

参考用户在其他 IDE 助手中见过的交互（`前段弹出 A/B/C/D 选项，D 是输入框，选完点提交`），
最终采用**方案 C：确定性哨兵值 + 前端显式按钮**，替代"猜测自由文本语义"：

| 路径 | 用户动作 | 后端识别方式 |
|---|---|---|
| 继续 | 点击"以当前信息继续分析" | 精确比较约定哨兵值 `HumanGate.CONTINUE_WITH_CURRENT_INFO`，**零 LLM 调用** |
| 补充 | 在输入框打字提交 | 走原有 `answer` 通道，累加进原文重新分析 |
| 退出 | 点击"结束本次面试" | 复用已有 `quit_interview` → `UserQuitException` 终止路径 |

优点：确定性、零额外 LLM 调用、零误判风险；用户点击的就是明确的按钮，不需要猜测意图。

## 3. 后端改动

### 3.1 `graph/HumanGate.java`
新增哨兵常量：
```java
public static final String CONTINUE_WITH_CURRENT_INFO = "__CLARIFY_CONTINUE_WITH_CURRENT_INFO__";
```
代表"用户点击了继续按钮"这个确定性信号，与真实用户输入文本不可能撞车。

### 3.2 `model/ClientMsg.java`
`type` 字段新增取值 `clarify_continue`。

### 3.3 `handler/WebSocketHandler.java`
新增消息分支与处理方法：
```java
case "clarify_continue" -> handleClarifyContinue(ws, msg);
```
```java
private void handleClarifyContinue(WSSession ws, ClientMsg msg) {
    if (ws.interviewRunning) {
        ws.answerCh.offer(HumanGate.CONTINUE_WITH_CURRENT_INFO);
    } else {
        sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("当前没有进行中的面试").build());
    }
}
```
与 `handleQuitInterview` 直接塞 `/quit` 是同一套模式：把"确定性动作"直接塞进 `answerCh`，
复用 `HumanGate.await` 的阻塞取值机制，`requestClarification` 侧无需感知这是按钮还是文本。

### 3.4 `agent/JDAnalyzer.java`
删除原来新增又废弃的 `isNoMoreInfo(String userReply)` 语义分类方法——改用哨兵值精确比较后，
不再需要这次额外的 LLM 调用。

### 3.5 `graph/Orchestrator.java` —— `jd_analysis` 澄清循环
判断逻辑从"调 LLM 判断这段话是不是表示没有更多信息"改为"精确比较是否是哨兵值"：
```java
if (HumanGate.CONTINUE_WITH_CURRENT_INFO.equals(supplement)) {
    c.cb.onStageChange("jd_analysis", "用户选择以当前信息继续，将基于现有信息继续分析。");
    break; // 提前结束澄清循环，走既有的"轮次耗尽仍不足"降级路径
}
```

## 4. 前端改动

### 4.1 `types/message.ts`
新增客户端消息类型：
```ts
| { type: 'clarify_continue' }
```

### 4.2 `components/MessageBubble.tsx`
`clarify_needed` 卡片改为渲染两个按钮 + 保留"输入框直接补充"的引导文案：

- **"以当前信息继续分析"** → 发送 `{ type: 'clarify_continue' }`
- **"结束本次面试"** → 发送 `{ type: 'quit_interview' }`（复用现有终止逻辑）

点击后按钮消失、显示"已选择：xxx"，防止重复点击。组件按 `stage` 无关地渲染，
对 `jd_analysis` / `resume_match` 等任意 stage 的 `clarify_needed` 消息统一生效，
**新增阶段接入该机制时前端零改动**。

### 4.3 `components/ChatWindow.tsx`
把 `send` 函数通过 `onSend` 传给 `MessageBubble`，供卡片内按钮调用。

## 5. 扩展到简历分析（resume_match）—— 本次新增

### 5.1 可行性结论

**可行，且是对现有机制的直接复用**：`HumanGate` / `requestClarification` /
`WebSocketHandler.handleClarifyContinue` / 前端 `ClarifyNeededCard` 全部按 `stage` 参数
泛化设计，接入新阶段**不需要改动任何一处已有代码**，只需要让 `resume_match` 阶段
产出与 `JDAnalysis` 同构的自评字段（`sufficient` / `missing_info` / `clarify_question`），
并在 `Orchestrator` 里补上与 `jdAnalysis` 完全同构的循环即可。

### 5.2 具体改动

**`model/ResumeMatchResult.java`** —— 新增自评字段（与 `JDAnalysis` 同构）：
```java
@JsonProperty("sufficient")
@Builder.Default
private boolean sufficient = true;

@JsonProperty("missing_info")
private List<String> missingInfo;

@JsonProperty("clarify_question")
private String clarifyQuestion;
```

**`agent/ResumeMatcher.java`** —— Prompt 增加自评规则：简历内容疑似乱码，或只有姓名/联系方式
没有任何工作经历/项目/技能中的任何一项时，`sufficient=false` 并给出 `missing_info` / `clarify_question`；
只是"经历简短"不算不足（与 JD 侧"不要仅因为 JD 简短就判定不足"的原则一致）。

**`graph/Orchestrator.java`** —— `resumeMatch` 从单次调用改为与 `jdAnalysis` 完全同构的澄清循环：
```java
private void resumeMatch(Ctx c) {
    c.cb.onStageChange("resume_match", "正在分析简历匹配度...");
    String resumeText = c.resumeText;
    c.resume = new Resume();
    c.resume.setRawText(resumeText);
    ResumeMatchResult result = resumeMatcher.match(c.jdAnalysis, c.resume);

    int round = 0;
    while (!result.isSufficient() && round < MAX_CLARIFY_ROUNDS) {
        round++;
        String question = ...; // result.getClarifyQuestion() 或默认文案
        String supplement = c.cb.requestClarification("resume_match", question);
        // UserQuitException/InterruptedException → 终止整条流程（同 jdAnalysis）

        if (HumanGate.CONTINUE_WITH_CURRENT_INFO.equals(supplement)) {
            break; // 用户点"继续"，提前结束循环
        }
        resumeText = resumeText + "\n\n【用户补充说明】" + supplement; // 累加不替换
        c.resume.setRawText(resumeText);
        result = resumeMatcher.match(c.jdAnalysis, c.resume);
    }
    // 轮次耗尽仍不足 → resume_match_partial 提示局限性，带着现有信息继续
    c.matchResult = result;
    ...
}

/** resume_match 之后的分支：用户在澄清环节主动退出 → 直接结束；否则继续走完整流程。 */
private String afterResumeMatch(Ctx c) {
    return c.userTerminated ? "end" : "continue";
}
```

图编排从固定边改为条件边（与 `jd_analysis` 同构，支持用户在澄清中退出时提前结束整条流程）：
```java
.addConditionalEdges("resume_match", edge_async(s -> afterResumeMatch(c)),
        Map.of("end", END, "continue", "question_plan"))
```

`MAX_CLARIFY_ROUNDS` 常量由 `jdAnalysis` 独占改为 `jd_analysis` / `resume_match` 共用。

### 5.3 前端 / WebSocketHandler

**零改动**。`requestClarification(stage, question)` 本身就接受任意 `stage` 字符串，
`ClarifyNeededCard` 按 `messageType === 'clarify_needed'` 渲染、不关心具体是哪个 stage，
`handleClarifyContinue` 只是往同一条 `answerCh` 塞哨兵值——三者都已经是泛化实现，
`resume_match` 接入后自动复用同一套"继续 / 补充 / 退出"三按钮交互。

## 6. 端到端流程（两个阶段通用）

```
LLM 分析/匹配 → sufficient=false
        │
        ▼
requestClarification(stage, question) ──► 前端渲染 clarify_needed 卡片
        │                                   ├─ 点击"继续" → 发 clarify_continue → answerCh 塞入哨兵值 → break 循环，降级继续
        │                                   ├─ 输入框打字提交 → 发 answer → 累加进原文 → 重新分析/匹配
        │                                   └─ 点击"结束面试" → 发 quit_interview → answerCh 塞 /quit → UserQuitException → 整条流程终止
        ▼
最多循环 MAX_CLARIFY_ROUNDS(2) 轮，轮次耗尽仍不足 → xxx_partial 提示局限性，带着现有信息继续
```

## 7. 涉及文件清单

| 文件 | 改动类型 |
|---|---|
| `graph/HumanGate.java` | 新增哨兵常量 |
| `model/ClientMsg.java` | 新增消息类型注释 |
| `handler/WebSocketHandler.java` | 新增 `clarify_continue` 分支处理 |
| `agent/JDAnalyzer.java` | 删除废弃的 `isNoMoreInfo` |
| `model/ResumeMatchResult.java` | 新增 `sufficient`/`missing_info`/`clarify_question` |
| `agent/ResumeMatcher.java` | Prompt 增加内容有效性自评规则 |
| `graph/Orchestrator.java` | `resume_match` 改为澄清循环 + 条件边；`jd_analysis` 判断逻辑改用哨兵值比较 |
| `types/message.ts`（前端） | 新增 `clarify_continue` 客户端消息类型 |
| `components/MessageBubble.tsx`（前端） | `clarify_needed` 卡片渲染三按钮交互 |
| `components/ChatWindow.tsx`（前端） | 传入 `onSend` |

## 8. 验证建议

- JD 只填标题（如"招 Java 工程师"）触发澄清 → 分别测试"继续"/"补充"/"退出"三条路径；
- 简历只粘贴姓名和一句话联系方式（长度刚好过 `MIN_RESUME_LENGTH=50` 但内容空洞）触发
  `resume_match` 澄清 → 同样验证三条路径；
- 补充信息后重新分析，确认原文未被替换（`【用户补充说明】` 是累加而非覆盖）；
- 连续 2 轮补充仍判定不足，确认能正确降级到 `jd_analysis_partial` / `resume_match_partial`
  并带着现有信息继续往下走，不会卡死等待。
