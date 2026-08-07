## Purpose

让每场面试的完整执行链路（7 个 Agent 节点 + ReactAgent 工具调用循环）在分布式追踪系统中以层级化 Trace 的形式可见，包含每一步的耗时和 LLM Token 消耗，无需手写任何埋点代码。

## ADDED Requirements

### Requirement: LLM 调用自动产生 Trace span

系统 SHALL 对所有通过 Spring AI `ChatModel.call()` / `ChatClient` 发起的 LLM 调用自动产生 Observation span，span 属性中包含模型名称、输入 Token 数、输出 Token 数和调用耗时，无需在业务代码中手写埋点。

#### Scenario: 单次 LLM 调用产生 span

- **WHEN** 任意 Agent（JDAnalyzer / ResumeMatcher / QuestionPlanner / Interviewer / Evaluator / ReviewPlanner）调用 `chatModel.call()`
- **THEN** OTLP 后端收到一个 span，包含 `gen_ai.usage.input_tokens`、`gen_ai.usage.output_tokens`、`gen_ai.model.name` 属性
- **AND** span 名称标识这是 LLM 调用

#### Scenario: ReactAgent 工具调用循环产生嵌套 span

- **WHEN** 出题 Agent（ReactAgent）在一次会话中调用 `search_question_bank` 工具 2 次 + `search_web` 工具 1 次
- **THEN** Trace 中呈现一个父 span（Agent 会话）下嵌套 3 个工具调用子 span + 对应的 LLM 调用子 span
- **AND** 每个工具调用 span 的名称包含工具名

### Requirement: 面试阶段产生层级化 Trace

系统 SHALL 为 `Orchestrator` 的每个节点方法（`jd_analysis` / `resume_match` / `question_plan` / `interview` / `weak_review` / `evaluation` / `review_plan`）产生一个阶段 span，该 span 作为父 span 包裹其内部的所有 LLM 调用子 span。

#### Scenario: 一场完整面试的 Trace 树

- **WHEN** 一场面试从 `jd_analysis` 跑到 `review_plan` 完成
- **THEN** Trace 后端呈现一棵以面试 sessionId 为根的 span 树
- **AND** 每个阶段是一个一级 span，其内部的 LLM 调用是嵌套的子 span
- **AND** 打开任意阶段 span 详情能看到该阶段的耗时和该阶段内所有 LLM 调用的 Token 总和

#### Scenario: 条件分支在 Trace 中可见

- **WHEN** 一场面试中 `weak_review` 节点被跳过（没有低分题）
- **THEN** Trace 中不出现 `weak_review` 的 span
- **AND** 可以通过"哪些阶段 span 存在"直接看出这场面试走了哪条分支

### Requirement: Trace 采样率可配置

系统 SHALL 支持通过配置文件设置 Trace 采样率，开发/演示环境默认全采样（1.0），生产环境可降低采样率。

#### Scenario: 开发环境全采样

- **WHEN** `management.tracing.sampling.probability` 设为 `1.0`
- **THEN** 每次 LLM 调用都产生 span 并导出到 OTLP 后端

#### Scenario: OTLP 后端不可用时不影响主流程

- **WHEN** OTLP exporter endpoint 不可达（后端宕机 / 网络不通）
- **THEN** 面试流程正常完成，不抛异常
- **AND** 仅在日志中记录导出失败的 warn

### Requirement: Trace 按 sessionId 关联

系统 SHALL 在 Trace 的根 span 上标注当前面试的 `sessionId`，使得在 Trace 后端可以通过 sessionId 搜索到一场面试的完整调用链。

#### Scenario: 通过 sessionId 搜索面试

- **WHEN** 在 Trace 后端（如 Langfuse / Jaeger）搜索 `session.id=<某场面试的 sessionId>`
- **THEN** 返回该场面试从 `jd_analysis` 到 `review_plan` 的完整 Trace
