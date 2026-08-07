## Context

项目基于 Spring Boot 3.4.1 + Spring AI Alibaba 1.1.2.0，所有 LLM 调用走 DashScope（通义千问 qwen-plus）。当前 pom.xml 无任何可观测性依赖，application.yml 无 tracing 配置。所有业务信号（fallback / 覆盖率 / 打回轮次 / 配额补全）仅以 `log.info/warn` 散布在代码中。

Spring AI 从 1.0 起内置了基于 Micrometer Observation 的自动埋点能力——`ChatModel.call()` 内部会自动产生 Observation，包含 `gen_ai.usage.*` 属性。这意味着 LLM 调用的 span **零代码接入**，只需引入依赖 + 配置 exporter。

## Goals / Non-Goals

**Goals:**
- 每场面试的 LLM 调用链在 OTLP 兼容后端（Langfuse / Jaeger / ARMS）以层级 Trace 可见
- 关键业务信号从日志升级为可查询的 Micrometer 指标
- 业务代码零改动（只在节点方法上加注解 + 在关键位置加指标埋点）

**Non-Goals:**
- 不做 Graph Checkpoint 持久化（那是 A1 的范围）
- 不改变任何 Agent prompt 或业务逻辑
- 不搭建 Grafana / Prometheus 仪表板（本变更只产出指标，仪表板是后续运维工作）
- 不做 Real User Monitoring（前端性能监控不在本次范围）

## Decisions

### 决策 1：用 Spring AI 原生 Observation，不自研埋点

**选择**: 引入 `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`，利用 Spring AI ChatModel 内置的 Observation 自动产生 LLM 调用 span。

**理由**: Spring AI 的 `ChatModel.call()` 内部已经包了 `ObservationRegistry`，每次调用自动产生一个带 `gen_ai.model.name` / `gen_ai.usage.input_tokens` / `gen_ai.usage.output_tokens` 的 span。自己写埋点是重复造轮子，且容易漏埋。

**备选方案（否决）**: 自研 AOP 拦截 `chatModel.call()` —— 需要自己解析 token 数、维护切面，且 Spring AI 版本升级后切点可能失效。

### 决策 2：阶段 span 用 `@Observed` 注解，不用手动 Observation API

**选择**: 在 `Orchestrator` 的 7 个节点方法上加 `@Observed(name = "interview.stage.<节点名>")` 注解。

**理由**:
- 注解方式零侵入，且 span 名称与方法名一一对应
- 阶段 span 自动包裹其内部所有 LLM 调用子 span（因为 Observation 是 thread-local 传播的）
- 面试跑在独立线程池（`asyncExecutor`），需要确认 `@Observed` 在跨线程时 observation context 传播正常

**备选方案（否决）**: 手动 `ObservationRegistry.observation().start()` / `.stop()` —— 代码侵入大，且每个节点方法要包 try-finally。

**⚠️ 风险**: `@Observed` 依赖 Spring AOP 代理。`Orchestrator` 的节点方法是被 `StateGraph` 通过 `node_async(s -> { jdAnalysis(c); ... })` 闭包调用的，不是 Spring 代理直接调用。**需要验证 @Observed 在这种间接调用下是否生效**——如果不生效，退回手动 Observation API。

### 决策 3：OTLP 后端选 Langfuse（开发）+ ARMS（生产）

**选择**: 开发环境用 Langfuse Docker 自建（开源、专做 LLM 可观测、支持 Prompt/Response 回放）；生产环境可接阿里云 ARMS AI 可观测。

**理由**:
- Langfuse 兼容标准 OTLP 协议，且专做 LLM 场景（按 Agent 维度看 Token 成本、Prompt/Response 回放）
- ARMS 是阿里云现成方案，与 DashScope 同在阿里云生态，网络直连
- 两者都兼容 OTLP，切换后端只改 `otlp.exporter.endpoint`，不改代码

**备选方案（否决）**: Grafana Tempo + Loki —— 通用 trace 后端，但不针对 LLM 场景优化，看不到 Prompt/Response 回放。

### 决策 4：业务指标用 Micrometer Counter / DistributionSummary，不用自定义日志解析

**选择**: 在关键位置（`fellBack()` / `logWeakPointCoverage()` / 审题打回 / 配额补全）直接注入 `MeterRegistry`，调 `counter()` / `summary()` 记录指标。

**理由**:
- Micrometer 指标天然兼容 Prometheus，`/actuator/prometheus` 端点自动暴露
- 比日志解析可靠（日志格式变了解析就崩）
- 标签维度（target/actual difficulty、cell key）天然支持分组聚合

**备选方案（否决）**: 用结构化 JSON 日志 + Loki 解析 —— 需要维护解析规则，且聚合查询能力远不如 PromQL。

### 决策 5：Trace 采样率默认 1.0（全采样），生产可降

**选择**: `management.tracing.sampling.probability=1.0`

**理由**: 面试是低频高价值场景（每天可能就几十场），不是高 QPS 服务，全采样的性能开销可忽略。生产环境如果量上来了再降到 0.1。

### 决策 6：OTLP 导出失败 fail-open

**选择**: OTLP 后端不可达时，面试流程正常完成，仅日志 warn。

**理由**: 可观测性是质量增强不是准入门槛（与审题 fail-open 同一原则）。后端挂了不能让面试跑不了。

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| `@Observed` 在 StateGraph 闭包调用下可能不生效（AOP 代理限制） | 先写一个验证测试；如果不生效，退回手动 `Observation.createNotStarted().start().stop()` 包裹 |
| Spring Boot 3.4 + OTLP exporter 版本兼容性 | 使用 Spring Boot 3.4 BOM 管理的版本，不手动指定 OTLP 版本 |
| Langfuse Docker 需要额外启动 | 提供 docker-compose 追加配置，与现有 Milvus/Redis/MySQL 共存 |
| 业务指标埋点增加代码侵入 | 只在已有 `log.info/warn` 的位置加一行 `meterRegistry.counter()`，不新增逻辑分支 |
| Trace 数据量可能偏大（一场面试 55~85 次 LLM 调用） | 全采样下每场 ~85 个 span，Langfuse 可承受；量大了降采样率 |

## Migration Plan

1. **加依赖** — pom.xml 加 `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`
2. **加配置** — application.yml 加 `management.tracing` 和 `otlp` 配置段
3. **加注解** — Orchestrator 7 个节点方法加 `@Observed`
4. **加埋点** — 5 处关键位置加 Micrometer 指标
5. **加配置类** — `ObservationConfig.java` 注册 MeterRegistry + OTLP exporter
6. **验证** — 跑一场面试，确认 Langfuse / Jaeger 能看到完整 Trace + Prometheus 能查到指标
7. **回滚策略** — 删除依赖 + 配置 + 注解 + 埋点，业务代码无任何残留影响

## Open Questions

- `@Observed` 在 StateGraph `node_async` 闭包调用下是否生效？需在 Task 1 中验证。如果不生效，退回手动 Observation API（不影响 spec，只影响实现方式）。
- Langfuse 社区版 Docker 是否支持 OTLP/gRPC 协议？还是只支持 OTLP/HTTP？需在 Task 6 部署时确认。如果不支持 gRPC，改用 HTTP exporter。
