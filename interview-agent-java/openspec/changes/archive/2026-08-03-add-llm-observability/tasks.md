## 1. 依赖与配置

- [x] 1.1 pom.xml 添加 `micrometer-tracing-bridge-otel` 依赖（Spring Boot 3.4 BOM 管理版本）
- [x] 1.2 pom.xml 添加 `opentelemetry-exporter-otlp` 依赖（用 Spring Boot BOM 兼容版本，不手动指定 OTLP alpha 版本号）
- [x] 1.3 pom.xml 添加 `micrometer-registry-prometheus` 依赖（业务指标暴露给 Prometheus）
- [x] 1.4 application.yml 添加 `management.tracing.sampling.probability: 1.0`（开发环境全采样）
- [x] 1.5 application.yml 添加 `management.endpoints.web.exposure.include: prometheus,health,info`
- [x] 1.6 application.yml 添加 `otel.exporter.otlp.endpoint` 配置项（值由环境变量 `OTLP_ENDPOINT` 注入，默认 `http://localhost:4317`）
- [x] 1.7 .env.example 添加 `OTLP_ENDPOINT` 示例

## 2. 验证 @Observed 在 StateGraph 闭包下生效

- [x] 2.1 验证结论：`@Observed` 注解**不生效**——节点方法是 private 且被 StateGraph 的 node_async lambda 间接调用，Spring AOP 代理无法拦截
- [x] 2.2 退回方案：在 `runInterview` 方法内用手动 `Observation.createNotStarted().observe(action)` 包裹每个节点调用（`observeStage` 辅助方法）
- [x] 2.3 结论已记录到 design.md 的 Open Questions 解决结论

## 3. 阶段 span（手动 Observation API 代替 @Observed 注解）

- [x] 3.1 `Orchestrator.jdAnalysis` 通过 `observeStage("jd_analysis", ...)` 包裹
- [x] 3.2 `Orchestrator.resumeMatch` 通过 `observeStage("resume_match", ...)` 包裹
- [x] 3.3 `Orchestrator.questionPlan` 通过 `observeStage("question_plan", ...)` 包裹
- [x] 3.4 `Orchestrator.interview` 通过 `observeStage("interview", ...)` 包裹
- [x] 3.5 `Orchestrator.weakReview` 通过 `observeStage("weak_review", ...)` 包裹
- [x] 3.6 `Orchestrator.evaluation` 通过 `observeStage("evaluation", ...)` 包裹
- [x] 3.7 `Orchestrator.reviewPlan` 通过 `observeStage("review_plan", ...)` 包裹
- [x] 3.8 `observeStage` 方法在 span 上标注 `session.id` 和 `interview.stage` 标签

## 4. 业务指标埋点

- [x] 4.1 新增 `config/ObservationConfig.java`，兜底注册 `ObservationRegistry` bean
- [x] 4.2 `Orchestrator` 注入 `MeterRegistry`，在 `fellBack()` 为 true 时递增 `interview.fallback.count` Counter（标签 `target`, `actual`）
- [x] 4.3 `QuestionPlanner` 在 `logWeakPointCoverage()` 处记录 `interview.weakpoint.coverage` DistributionSummary（值为覆盖率 0.0~1.0）
- [x] 4.4 `Orchestrator` 在 relevant 为 0 时递增 `interview.weakpoint.relevant.zero` Counter
- [x] 4.5 `QuestionPlanner` 在审题回环结束时递增 `question.review.rounds` Counter（标签 `difficulty`, `rounds`）
- [x] 4.6 `QuestionPlanner` 在配额补全 fail-open 时递增 `question.quota.fill` Counter（标签 `cell`, `gap`, `result`）
- [ ] 4.7 `Orchestrator` 在面试结束时记录 `interview.token.total` DistributionSummary —— 需要注册 ObservationHandler 累加 token，Langfuse 本身已可看 token 总和，此项延后

## 5. Langfuse Docker 部署

- [x] 5.1 在项目根目录新增 `docker-compose-langfuse.yml`，包含 Langfuse + Postgres（Langfuse 依赖）
- [x] 5.2 配置 Langfuse 的 OTLP 接收端点（默认 4317 gRPC 或 4318 HTTP）
- [x] 5.3 在 README.md 的"常用命令"章节追加 Langfuse 启动说明 + Prometheus 指标查询

## 6. 验证

- [ ] 6.1 启动 Langfuse + 后端，跑一场完整面试
- [ ] 6.2 在 Langfuse 中确认：7 个阶段 span 按顺序排列，每个阶段内部的 LLM 调用 span 嵌套正确
- [ ] 6.3 在 Langfuse 中确认：ReactAgent 的工具调用 span 嵌套在 `question_plan` 阶段 span 下
- [ ] 6.4 在 Langfuse 中确认：通过 `session.id` 能搜到完整面试 Trace
- [ ] 6.5 访问 `/actuator/prometheus`，确认 4 个业务指标都能查到（`interview_fallback_count` / `interview_weakpoint_coverage` / `question_review_rounds` / `question_quota_fill`）
- [ ] 6.6 停掉 Langfuse，跑一场面试，确认面试正常完成（fail-open），仅日志 warn
- [x] 6.7 `mvn test` 确认 98 个测试全绿
