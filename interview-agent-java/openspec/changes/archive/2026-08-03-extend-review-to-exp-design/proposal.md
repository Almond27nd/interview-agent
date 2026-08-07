## Why

Phase 2 的 Agentic RAG 改造（B1 + B4）只覆盖了 `basic` 类方向（15 个）。`experience`（12 个）和 `design`（4 个）仍是手写 pipeline：一次性 LLM 批量出题，**没有题库检索、没有审题质检、没有回环**。16/31 = 52% 的方向没有质量保障，这在前一次 explore 分析中被标记为问题④。

## What Changes

- 把 `experience` 和 `design` 类方向的出题从"一次性 LLM 批量"改造为接入审题回环（复用 basic 的 `assemble ⇄ review → finalize` 子图）
- `experience` 类方向保留"严禁幻觉"约束——审题 Agent 需额外检查题目是否基于简历真实内容
- `design` 类方向审题需额外检查"追问是否真正考察架构取舍而非 API 用法"
- 审题规则预检（`QuestionRuleChecker`）对 experience/design 类增加方向适配的字段校验（如 experience 类必须有 `context` 字段）
- **不改动**：Phase 1 出方向逻辑、配额校验、ReactAgent 的工具列表、basic 类的已有出题-审题子图

## Capabilities

### New Capabilities
- `exp-design-review`: experience 和 design 类方向的审题质检——复用 basic 审题子图，按方向类型增加差异化检查维度

### Modified Capabilities
<!-- 无现有 spec（openspec/specs/ 为空），全部作为新 capability 处理 -->

## Impact

- **代码变更**:
  - `QuestionPlanner.java`：`assembleQuestions` 方法内，把 experience/design 方向也走 `assembleBasicQuestionsWithAgent` 的子图路径（需泛化方法签名，当前方法名含 "Basic" 需重命名或新增重载）
  - `QuestionRuleChecker.java`：增加 experience 类 `context` 字段非空校验、design 类 `follow_ups` 必须包含架构取舍类追问
  - `QuestionReviewer.java`：Critic instruction 增加按 type 分化的审查维度
- **不改动**: Orchestrator 业务逻辑、Phase 1、ReactAgent 工具、basic 类已有路径
- **LLM 调用次数影响**: experience 12 + design 4 = 16 个方向新增审题回环，每档 1~2 轮 Critic 调用（+3~6 次 LLM 调用），总面试 LLM 调用从 55~85 次增加到 58~91 次（+5%）
