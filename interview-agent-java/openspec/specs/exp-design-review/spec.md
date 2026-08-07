# exp-design-review Specification

## Purpose
让 experience（经验类）和 design（设计类）方向的题目也经过"出题 ⇄ 审题 → 定稿"回环质检，消除当前 52% 方向无质检的质量盲区，同时按方向类型施加差异化的审查维度。
## Requirements
### Requirement: experience 类方向接入审题回环

系统 SHALL 对所有 experience 类方向的题目执行与 basic 类相同的"出题 Agent → 规则预检 → 审题 Agent →（有打回则回炉）→ 定稿"回环流程，回环上限 `MAX_REVIEW_ROUNDS=2` 与 basic 一致。

#### Scenario: experience 方向题目通过审题

- **WHEN** Phase 2 组装 experience 类方向的题目，出题 Agent 产出草稿后送审
- **THEN** 规则预检 + 审题 Agent 均无异议
- **AND** 题目进入定稿

#### Scenario: experience 方向题目被审题打回后回炉

- **WHEN** 审题 Agent 判定某 experience 方向的题目"没有基于简历真实内容"
- **THEN** 该方向被打回，出题 Agent 依据打回理由重出
- **AND** 重出后再次送审，最多 2 轮，轮次耗尽 fail-open

### Requirement: experience 类审题增加"简历真实性"维度

审题 Agent SHALL 对 experience 类题目额外检查：题目是否严格基于候选人简历中的真实项目/经历，不得杜撰简历中未提及的技术细节。

#### Scenario: experience 题目杜撰了简历不存在的项目

- **WHEN** experience 类题目询问"你在微服务拆分项目中是如何做服务治理的"，但简历中未提及微服务拆分项目
- **THEN** 审题 Agent 判定 reject
- **AND** 打回理由明确指出"简历中未找到该项目，疑似杜撰"

#### Scenario: experience 题目基于简历真实内容

- **WHEN** experience 类题目询问简历中明确提到的技术栈相关的问题
- **THEN** 审题 Agent 在"简历真实性"维度判定 pass

### Requirement: design 类审题增加"架构取舍"维度

审题 Agent SHALL 对 design 类题目额外检查：追问是否真正考察架构取舍能力（如"为什么选 A 不选 B"），而非简单的 API 用法或记忆题。

#### Scenario: design 追问只问 API 用法

- **WHEN** design 类题目的 `follow_ups` 是"How do you use Redis?"这类 API 用法题
- **THEN** 审题 Agent 判定 reject
- **AND** 打回理由指出"追问未考察架构取舍能力"

#### Scenario: design 追问考察架构取舍

- **WHEN** design 类题目的 `follow_ups` 是"如果缓存雪崩，你会选择限流降级还是多级缓存？为什么？"这类取舍题
- **THEN** 审题 Agent 在"架构取舍"维度判定 pass

### Requirement: 规则预检按方向类型差异化

`QuestionRuleChecker` SHALL 对 experience 类方向增加 `context` 字段非空校验（experience 方向必须携带简历上下文），对 design 类方向增加 `follow_ups` 至少 2 条的校验（设计题需要更深入的追问链）。

#### Scenario: experience 方向缺少 context 字段

- **WHEN** 规则预检发现某 experience 方向的草稿 `context` 字段为空
- **THEN** 规则层直接报缺陷（不花 Critic 的 token）
- **AND** 缺陷类型为 `MISSING_CONTEXT`

#### Scenario: design 方向只有 1 条追问

- **WHEN** 规则预检发现某 design 方向的草稿 `follow_ups` 只有 1 条
- **THEN** 规则层报缺陷
- **AND** 缺陷类型为 `FOLLOW_UPS_TOO_FEW`

### Requirement: experience/design 出题可降级为原有 pipeline

系统 SHALL 在 ReactAgent 不可用或整张子图异常时，对 experience/design 方向降级为原有的"一次性 LLM 批量出题"路径，保证面试不会因审题组件故障而无题可问。

#### Scenario: 审题 Agent 调用异常

- **WHEN** `QuestionReviewer.review()` 抛出异常
- **THEN** 该档 experience/design 方向视为全部通过（fail-open）
- **AND** 日志记录"审题失败，本档视为全部通过"

#### Scenario: ReactAgent 不可用

- **WHEN** `questionBankTool` 未配置或不可用
- **THEN** experience/design 方向退回原有的 `assembleQuestions` 一次性批量出题
- **AND** 日志记录降级

