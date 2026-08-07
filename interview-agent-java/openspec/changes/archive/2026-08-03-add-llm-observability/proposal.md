## Why

项目目前有 98 个测试全绿，技术密度很高（Multi-Agent 回环、Router、证据型记忆、三路召回），但**完全没有可观测性**——跑一场面试时不知道调了几次 LLM、每次花多少 token、哪个 Agent 耗时最长。M4/M5 的改动效果没有数据能证明，`fellBack()`（难度 fallback）和 `logWeakPointCoverage()`（覆盖率）这些关键信号只能人工翻日志。**短板已经从"功能不够"变成"说不出效果"**，而接可观测性是投入最小（加依赖 + 配置）、叙事价值最大的改法。

## What Changes

- 引入 `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` 依赖，启用 Spring AI 原生的 `Observation` 能力（ChatModel / ChatClient 内置，零代码接入）
- 配置 OTLP exporter endpoint（支持自建 Langfuse / Grafana Tempo / 阿里云 ARMS）
- 给 `Orchestrator` 的 7 个节点方法用 `@Observed` 注解手动包一层 span，让一场面试的 Trace 树呈现"阶段 span 包裹 LLM 调用 span"的层级结构
- 把散落在代码里的关键业务信号（`fellBack()`、`logWeakPointCoverage()`、审题打回轮次、配额补全触发）转为 Micrometer 业务指标（Counter / DistributionSummary），可通过 Prometheus + Grafana 长期跟踪
- **不改变任何业务逻辑**——纯横切层接入，业务代码零改动（只在节点方法上加注解 + 在关键位置加指标埋点）

## Capabilities

### New Capabilities
- `llm-trace`: LLM 调用全链路 trace——每场面试的 7 个 Agent 节点、ReactAgent 的工具调用循环都能在 Trace 里按时间线和层级看到，包括每一步的 Token 消耗和耗时
- `business-metrics`: 业务可观测指标——单场面试总 Token 消耗、各 Agent 平均 Token 占比、难度 fallback 触发率、薄弱点覆盖率、审题打回轮次分布、配额补全触发率

### Modified Capabilities
<!-- 无——本次变更不改变任何现有 spec 级行为，纯横切层接入 -->

## Impact

- **新增依赖**: `micrometer-tracing-bridge-otel`、`opentelemetry-exporter-otlp-1.43.0-alpha`（Spring Boot 3.4 + Spring AI 1.x 兼容版本）
- **配置变更**: `application.yml` 新增 `management.tracing.sampling.probability` 和 `otlp.exporter.endpoint`
- **代码变更**（仅横切层）:
  - `Orchestrator.java` 的 7 个节点方法加 `@Observed` 注解
  - `Orchestrator.java` 在 `fellBack()` / `logWeakPointCoverage()` 处加 Counter/DistributionSummary 埋点
  - `QuestionPlanner.java` 在审题打回 / 配额补全处加 Counter 埋点
  - 新增 `config/ObservationConfig.java`（ObservationRegistry 配置 + 自定义业务指标 bean）
- **不改动**: 任何业务逻辑、Agent prompt、Graph 结构、测试用例行为
- **环境要求**: 需要一个 OTLP 兼容的后端（开发环境用 Langfuse Docker 自建，生产可接阿里云 ARMS）
