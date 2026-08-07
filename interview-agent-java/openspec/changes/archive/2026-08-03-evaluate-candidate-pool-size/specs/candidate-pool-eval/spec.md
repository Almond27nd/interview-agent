## Purpose

量化评估记忆召回候选池大小（当前 `WEAK_POINT_CANDIDATE_POOL=30`）对召回质量的影响，通过构造"大候选池 + 强相关项排在 priority 榜尾"的场景数据集，回答"30 是不是最优"这个至今只能靠猜的问题。

## ADDED Requirements

### Requirement: 构造大候选池评估样本组（组 J）

系统 SHALL 在评估数据集中新增一组样本（组 J），每样本包含 15~30 个候选薄弱点，其中强相关项（`relevant_topics`）刻意被设置为高 `mastery`、非顽固、低错误率——使其 `priority()` 排名落在榜尾，模拟"跨岗位场景下强相关项被无关顽固点挤掉"的真实情况。

#### Scenario: 组 J 样本构造正确

- **WHEN** 加载组 J 的某个样本
- **THEN** 该样本的 `candidates` 数量 ≥ 15
- **AND** 至少一个 `relevant_topics` 里的 topic，其 `priority()` 值排在候选集的后 50%
- **AND** 候选集中存在 ≥ 8 个非 relevant 的候选（模拟跨岗位的无关顽固点占满前排）

#### Scenario: 组 J 样本的 jd_skills 不从同义词表抄词

- **WHEN** 组 J 样本的 `jd_skills` 与 `MemoryWriteGate` 同义词表的 key/value 做比对
- **THEN** 泄漏率 ≤ 0.30（低于现有 0.60 上限，组 J 作为新样本应更严格）
- **AND** `LeakageReport` 对组 J 单独报告泄漏率

### Requirement: 按 pool 大小分组评估

系统 SHALL 对组 J 样本分别在不同候选池大小（pool = 10, 15, 20, 25, 30）下运行召回评估，对比各 pool 值下的 Recall@3、MRR、Precision，输出敏感性分析。

#### Scenario: pool=10 时强相关项被挤掉

- **WHEN** 组 J 样本在 pool=10 下运行召回
- **THEN** 排在 priority 榜尾的强相关项**不进入候选池**（被截断在 10 条之外）
- **AND** Recall@3 显著低于 pool=30 时的值

#### Scenario: pool=30 时强相关项进入候选池

- **WHEN** 同一样本在 pool=30 下运行召回
- **THEN** 强相关项进入候选池（30 条内）
- **AND** 三路召回（词法/语义/记忆）有机会把它排进 Top10
- **AND** Recall@3 高于 pool=10 时的值

#### Scenario: pool 值继续增大不再有收益

- **WHEN** pool 从 30 增大到 50
- **THEN** Recall@3 不再显著提升（候选池已经覆盖了所有可能相关的项）
- **AND** Precision 可能下降（更多无关项进入候选，干扰 RRF 排名）

### Requirement: 候选池敏感性分析报告

系统 SHALL 在评估报告中输出候选池大小敏感性分析段，包含：不同 pool 值下的 Recall@3 / MRR / Precision 表格、最优 pool 值建议、以及"30 是否足够"的明确结论。

#### Scenario: 报告包含 pool 敏感性分析

- **WHEN** 评估完成后查看报告
- **THEN** 报告包含一个表格，行 = pool 值（10/15/20/25/30/50），列 = Recall@3 / MRR / Precision
- **AND** 报告包含一段结论："pool=X 时 Recall@3 达到拐点，再增大无显著收益，建议保持/调整 WEAK_POINT_CANDIDATE_POOL = X"

#### Scenario: 报告标注现有数据集（组 A~I）的局限

- **WHEN** 报告展示组 A~I（每样本 2~4 候选）的指标
- **THEN** 报告明确标注"组 A~I 候选数 < 10，pool 大小对它们无影响，以下敏感性分析仅基于组 J"
