## Context

现有评估数据集 60 个样本（组 A~I），每样本候选 2~4 条。`MemoryRecallEvaluator` (L180) 用 `toWeakPoints(sample)` 直接构造候选列表，**不经过 `LongTermMemory.getWeakPointCandidates`** 的 pool 截断——所以 pool=10→30 在现有评估里**不可能有任何影响**（交接文档 15.3 节已明确指出）。

`MemoryEvalDatasetTemplate` (L68-74) 用 `c(topic, score, hit, wrong, stubborn, daysAgo, difficulty)` 声明式构造候选，加新样本只需加 `MemoryEvalSample.builder()` 调用。

`priority()` 的计算（`UserProfile.java` L196-205）：`p = 1 - mastery() + stubborn?0.35:0 + min(0.3, relapse*0.15) + min(0.2, wrongRate*0.2)`。要让强相关项排在榜尾，需要给它高 mastery（低 p）+ 非顽固 + 低错误率，同时给无关项低 mastery + stubborn（高 p）。

## Goals / Non-Goals

**Goals:**
- 构造一组"大候选池 + 强相关项排在 priority 榜尾"的样本
- 在不同 pool 值下量化 M4 的收益
- 给出"30 是否最优"的数据支撑结论

**Non-Goals:**
- 不改 `WEAK_POINT_CANDIDATE_POOL` 常量（结论出来后如果需要改，是后续独立变更）
- 不改 `MemoryRecallService` 的召回逻辑
- 不改 `priority()` 的权重（那是另一个独立的"无实验支撑"短板）

## Decisions

### 决策 1：组 J 构造 10~15 个样本，每样本 20~30 个候选

**选择**: 10~15 个样本，每样本 20~30 个候选，其中 3~5 个是 `relevant_topics`（强相关但 priority 低），15~25 个是无关顽固点（priority 高但与 JD 不相关）。

**理由**:
- 10~15 个样本足够看出 pool=10 vs 30 的指标差异
- 20~30 个候选确保 pool=10 会截断掉部分强相关项，pool=30 不会
- 3~5 个 relevant 保证 Recall@3 有区分度（relevant 太少 @3 无意义）

**构造模板**:
```
样本结构：
  jd_skills: ["mysql索引", "redis持久化", "spring事务"]  ← Java 岗位
  candidates:
    - 15 个 Go 相关薄弱点（score 40~55, stubborn=true, wrongCount≥3）→ priority 高但与 JD 无关
    - 3 个 Java 强相关项（score 65~75, stubborn=false, wrongCount=1）→ priority 低但与 JD 强相关
    - 5 个其他领域薄弱点（score 50~60, 各种参数）→ 填充候选池
  relevant_topics: 那 3 个 Java 强相关项
  预期：pool=10 时 3 个 Java 项被 Go 项挤掉；pool=30 时进入候选池
```

### 决策 2：pool 敏感性分析通过参数化评估实现，不改生产代码

**选择**: 在 `MemoryRecallEvaluator` 新增 `evaluateWithPoolSize(List<WeakPoint> allCandidates, int poolSize)` 方法——模拟 `LongTermMemory.getWeakPointCandidates` 的 pool 截断，但**在评估代码里做**，不改生产代码。

**理由**:
- 生产代码的 `WEAK_POINT_CANDIDATE_POOL=30` 是编译期常量，不能动态改
- 评估要对比不同 pool 值，需要参数化
- 在评估侧模拟截断 = 复制 `topByPriority(candidates, poolSize)` 的逻辑（只有一行 `sort + limit`）

**备选（否决）**: 把 `WEAK_POINT_CANDIDATE_POOL` 改成可配置的——为了评估改生产代码，违反最小侵入原则。

### 决策 3：泄漏率红线对组 J 更严（≤0.30）

**选择**: 组 J 的 `jd_skills` 不能从同义词表抄词，泄漏率红线从 0.60 收紧到 0.30。

**理由**: M3 泄漏事故的教训——"加一条规则"和"记住一个答案"在实现上是同一个动作。组 J 是新构造的样本，更应避免泄漏。0.30 比现有 0.60 更严，给新样本留更小的泄漏空间。

### 决策 4：pool 扫描范围为 [10, 15, 20, 25, 30, 40, 50]

**选择**: 7 个采样点，覆盖"当前值(10/30) + 中间值 + 过冲值(40/50)"。

**理由**:
- 10 = M4 改造前的值
- 30 = 当前值
- 40/50 = 验证"继续增大是否有收益"（预期 Precision 下降）
- 15/20/25 = 寻找拐点

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| 组 J 样本的 `jd_skills` 意外与同义词表重叠 | 构造时先跑 `LeakageReport`，泄漏率 > 0.30 就调整 jd_skills 用词 |
| 10~15 个样本不够统计显著 | 这是离线评估不是 A/B 测试，指标差异是方向性的（pool=10 Recall@3 显著低于 30 就够了），不需要 p-value |
| 组 J 的"强相关项 priority 低"构造可能不真实 | 记录构造逻辑的 `note` 字段，标注这是刻意构造的压力测试场景；真实场景中"跨岗位 + 强相关但低 priority"确实存在（交接文档 15.3 节的例子） |

## Migration Plan

1. 在 `MemoryEvalDatasetTemplate` 新增组 J 样本
2. 跑泄漏率检查，确认 ≤ 0.30
3. `MemoryRecallEvaluator` 新增 pool 参数化评估方法
4. `MemoryEvalReportRenderer` 新增敏感性分析报告段
5. 跑评估，记录结果
6. 根据结果决定是否创建后续变更（调整 `WEAK_POINT_CANDIDATE_POOL`）

## Open Questions

- 组 J 的岗位覆盖应该集中在 Java（与现有组 A~I 一致）还是覆盖 Go/Python 等跨岗位场景？倾向跨岗位（因为 M4 的核心场景就是跨岗位），但跨岗位的 `jd_skills` 更难避免与同义词表重叠。待 Task 1 构造时决定。
