## Why

`WEAK_POINT_CANDIDATE_POOL = 30` 是 M4 层次修正时拍定的经验值，没有任何实验数据支撑。M4 的核心价值——"把截断从不知道 JD 的层挪到知道 JD 的层，避免跨岗位场景下强相关项被 Go 薄弱点占满"——因评估数据集每样本只有 2~4 条候选而**完全无法量化验证**。交接文档明确将此列为短板："要真正量化收益，需给数据集补一组'候选 >10 且强相关项排在 priority 榜尾'的样本（组 J）。这是尚未做的独立工作。"

## What Changes

- 在现有 60 样本评估数据集基础上，新增一组**"大候选池"样本（组 J）**：每样本 15~30 个候选，其中强相关项刻意给高 `mastery`、非顽固、低错误率（即 `priority` 排在榜尾），构造 M4 要修的真实场景
- 扩展 `MemoryRecallEvaluator` 支持按候选池大小分组对比：分别在 `pool=10/15/20/25/30` 下跑评估，对比 Recall@3 / MRR / Precision
- 新增"候选池大小敏感性分析"报告：输出不同 pool 值下的指标曲线，回答"30 是不是最优"
- **不改动**生产代码中的 `WEAK_POINT_CANDIDATE_POOL` 常量值——本变更是评估工作，结论可能确认 30 合理、也可能建议调整，但调整本身是后续变更

## Capabilities

### New Capabilities
- `candidate-pool-eval`: 候选池大小敏感性评估——构造大候选池场景数据集，在不同 pool 值下跑离线评估，量化 M4 的收益并寻找最优值

### Modified Capabilities
<!-- 无 -->

## Impact

- **新增数据**: `memory/eval/MemoryEvalDatasetTemplate` 新增组 J（约 10~15 个样本）
- **代码变更**:
  - `MemoryRecallEvaluator`：新增按 pool 大小分组评估的方法
  - `MemoryEvalReportRenderer`：新增候选池敏感性分析的报告段
- **不改动**: `LongTermMemory.WEAK_POINT_CANDIDATE_POOL` 常量、`MemoryRecallService` 召回逻辑、生产代码
- **红线**: 新样本的 `jd_skills` **绝不能**从同义词表里抄词（M3 泄漏事故的教训），需跑完先查 `LeakageReport` 泄漏率
