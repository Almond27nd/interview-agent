## Context

当前 `QuestionPlanner` 有两条出题路径：
1. **basic 类**（15 方向）：`assembleBasicQuestionsWithAgent()` — ReactAgent + 审题子图（B4）
2. **experience + design 类**（16 方向）：`assembleQuestions()` — 一次性 LLM 批量出题，无检索、无审题、无回环

`assembleQuestions` (L457-502) 的实现：把所有方向拼进一个 Prompt，调一次 `chatModel.call()`，解析 JSON 返回。没有 `QuestionRuleChecker`，没有 `QuestionReviewer`，没有 `MAX_REVIEW_ROUNDS`。

basic 类的审题子图在 `assembleBasicQuestionsWithAgent` (L669-726) 内部，按 difficulty 分组，每组跑一张 `StateGraph(assemble ⇄ review → finalize)`。子图的 `GroupCtx` 持有 `ReactAgent` 实例、对话历史、草稿、缺陷列表。

## Goals / Non-Goals

**Goals:**
- experience/design 方向接入审题回环，复用 basic 的子图架构
- 按方向类型施加差异化审查维度（experience 查简历真实性，design 查架构取舍）
- 规则预检按 type 分化

**Non-Goals:**
- 不给 experience/design 接入题库检索（experience 应基于简历而非题库；design 类题库覆盖不足，检索意义不大）
- 不改 Phase 1 出方向逻辑
- 不改 basic 类已有路径
- 不改 `MAX_REVIEW_ROUNDS=2`

## Decisions

### 决策 1：复用子图，不新建第二套

**选择**: 把 `assembleBasicQuestionsWithAgent` 泛化（重命名为 `assembleWithReview` 或新增重载），让它接受任意 type 的方向列表。

**理由**: basic 的子图架构（assemble ⇄ review → finalize + GroupCtx + 条件回环）和 type 无关——它只管"出题 → 审题 → 打回回炉"。type 差异化在 `QuestionRuleChecker` 和 `QuestionReviewer` 里处理，不在子图结构里。

**备选（否决）**: 为 experience/design 新建一套平行的子图——代码重复，维护两套回环逻辑。

### 决策 2：experience 类不挂检索工具

**选择**: experience 方向的出题 Agent **不挂 `search_question_bank` 工具**，只用 LLM 基于简历内容出题。design 类同理。

**理由**:
- experience 题必须基于候选人简历真实内容，题库里的题是别人的，不适用
- design 类题库覆盖不足，检索意义不大，反而可能引入不相关的题
- 审题的"简历真实性"维度才是 experience 类的核心质量门禁

**影响**: experience/design 的出题 Agent 是"无工具 ReactAgent"——等价于单轮 `chatModel.call`（如 `QuestionReviewer` 的先例）。可以考虑直接用单轮调用 + 审题子图，不走 ReactAgent。

### 决策 3：差异化审查维度用 prompt 分化，不用代码 if-else

**选择**: `QuestionReviewer` 的 instruction 里按 type 列出不同的审查维度，让 LLM 自行根据题目的 `type` 字段选择适用维度。

**理由**: 审查维度是语义判断，适合 LLM 处理。代码 if-else 会让 `QuestionReviewer` 变成一堆分支，且每加一个 type 就要改代码。

**备选（否决）**: 为 experience/design 各写一个 `QuestionReviewer` 子类——过度工程化，审查逻辑的差异不大。

### 决策 4：规则预检的差异化用代码

**选择**: `QuestionRuleChecker` 里对 experience 类检查 `context` 非空、对 design 类检查 `follow_ups` ≥ 2，用代码 if-else。

**理由**: 这两条是**机械可判定**的（字段空/数量不够），属于规则层职责。与项目既有原则一致："机械可判定的用规则，需要理解语义的才交给模型"。

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| experience 出题 Agent 无工具 = 单轮 LLM，审题回环的"回炉重出"可能只是换个说法重写 | 回炉指令明确要求"换角度提问"，且审题的"简历真实性"维度能抓住杜撰；如果重出仍不达标，2 轮后 fail-open |
| LLM 调用次数 +5%（+3~6 次 Critic 调用） | 可接受——审题是质量增强，且 Critic 是单轮调用无工具，成本远低于出题 Agent |
| `assembleBasicQuestionsWithAgent` 重命名可能影响已有调用方 | 方法是 `QuestionPlanner` 内部 private 调用，`Orchestrator` 只调 `assembleQuestions` 入口，改名无外部影响 |
| design 类只有 4 个方向（medium 2 + hard 2），分档后每档只有 2 个方向，审题回环的"跨方向重复"检测意义不大 | 保留回环——重复检测只是审题的一个维度，难度诚实/追问递进等维度仍有价值 |

## Migration Plan

1. `QuestionRuleChecker` 增加 experience/design 的差异化规则
2. `QuestionReviewer` instruction 增加按 type 分化的审查维度
3. `QuestionPlanner.assembleQuestions` 内部，把 experience/design 方向也走子图路径
4. 验证 basic 类路径不受影响
5. 回滚策略：恢复 `assembleQuestions` 原有批量路径即可

## Open Questions

- experience 出题用"单轮 LLM + 审题子图"还是"无工具 ReactAgent + 审题子图"？两者行为等价（无工具的 ReactAgent = 单轮调用），但 ReactAgent 多建一张 StateGraph。倾向单轮调用（与 `QuestionReviewer` 同构），待 Task 实现时确认。
