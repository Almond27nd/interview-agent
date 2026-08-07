# business-metrics Specification

## Purpose
将散落在代码日志中的关键业务信号（难度 fallback 触发率、薄弱点覆盖率、审题打回轮次、配额补全触发）转为结构化的 Micrometer 业务指标，可通过 Prometheus + Grafana 长期跟踪趋势，让"记忆召回到底影响了多少最终出题""配额缺口发生频率多高"这些问题第一次变成可查询的数据。
## Requirements
### Requirement: 难度 fallback 触发率指标

系统 SHALL 对每次 `StageScheduler.Picked.fellBack()` 为 true 的取题事件递增一个 Micrometer Counter，标签包含目标难度和实际难度，使得可以查询"fallback 触发率 = fallback 次数 / 总取题次数"。

#### Scenario: 候选人连错两题后难度降级但 easy 桶为空

- **WHEN** `StageScheduler` 目标难度为 easy 但 easy 桶为空，fallback 取到 medium 的题
- **THEN** Counter `interview.fallback.count` 递增 1，标签 `target=easy,actual=medium`
- **AND** 该指标可在 Prometheus 中通过 `rate(interview_fallback_count[5m])` 查询触发率

### Requirement: 薄弱点覆盖率指标

系统 SHALL 在 Phase 1 完成后记录一个 DistributionSummary，值为本场强相关薄弱点被方向覆盖的比例（0.0~1.0），使得可以长期跟踪"召回排出的薄弱点实际被 LLM 采纳了多少"。

#### Scenario: 召回 8 条强相关薄弱点，方向覆盖了 5 条

- **WHEN** Phase 1 的 `logWeakPointCoverage()` 计算出覆盖率 = 5/8 = 0.625
- **THEN** DistributionSummary `interview.weakpoint.coverage` 记录值 0.625
- **AND** Grafana 可查看该指标的历史趋势（均值 / p50 / p90）

#### Scenario: 召回 0 条强相关薄弱点

- **WHEN** 记忆召回返回的 relevant 列表为空
- **THEN** 指标不记录（避免除零），仅日志告警
- **AND** Counter `interview.weakpoint.relevant.zero` 递增 1，标记"本场无强相关薄弱点"

### Requirement: 审题打回轮次指标

系统 SHALL 在 Phase 2 审题回环结束时记录一个 Counter，标签包含难度档（easy/medium/hard）和实际打回轮次数（0/1/2），使得可以统计"审题回环的触发频率和分布"。

#### Scenario: hard 档经历 2 轮打回后 fail-open 定稿

- **WHEN** hard 档的出题-审题子图经历 2 轮回炉后仍有缺陷，采纳当前版本
- **THEN** Counter `question.review.rounds` 递增 1，标签 `difficulty=hard,rounds=2`

#### Scenario: easy 档一轮通过

- **WHEN** easy 档的出题-审题子图首轮即全 pass
- **THEN** Counter `question.review.rounds` 递增 1，标签 `difficulty=easy,rounds=0`

### Requirement: 配额补全触发指标

系统 SHALL 在 Phase 1 配额校验触发 LLM 补全时记录一个 Counter，标签包含缺口的格子 key（如 `basic/easy`），使得可以统计"配额补全的触发频率和缺口分布"。

#### Scenario: Phase 1 首轮 basic/easy 缺 3 条，补全成功

- **WHEN** `DirectionQuotaChecker.diff()` 返回 `basic/easy: 3`，`callGapFiller()` 补全后达标
- **THEN** Counter `question.quota.fill` 递增 1，标签 `cell=basic/easy,gap=3,result=success`

#### Scenario: 配额补全后仍有缺口，fail-open

- **WHEN** 补全一轮后 `diff()` 仍返回非空
- **THEN** Counter `question.quota.fill` 递增 1，标签 `cell=<缺口格子>,gap=<缺口数>,result=fail_open`
- **AND** 该指标让"配额补全到底有没有用"第一次可量化

### Requirement: 单场面试 Token 总消耗指标

系统 SHALL 在一场面试结束时记录一个 DistributionSummary，值为本场所有 LLM 调用的 input_tokens + output_tokens 总和，标签包含面试题数，使得可以长期跟踪"单场面试的 Token 成本趋势"。

#### Scenario: 一场 15 题面试消耗 45000 token

- **WHEN** 面试完成（`review_plan` 节点结束）
- **THEN** DistributionSummary `interview.token.total` 记录值 45000，标签 `questions=15`
- **AND** Grafana 可查看单场 Token 成本的 p50 / p90 趋势

