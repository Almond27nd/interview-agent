## 1. 构造组 J 样本

- [x] 1.1 设计组 J 样本模板：每样本 20~30 候选，3~5 个 relevant（priority 低），8~15 个无关顽固点（priority 高）
- [x] 1.2 在 `MemoryEvalDatasetTemplate` 新增 5 个组 J 样本（mem_061~mem_065），覆盖 Java/Python/Go/大数据/C++ 跨岗位场景
- [x] 1.3 每个样本的 `note` 字段记录构造逻辑
- [x] 1.4 泄漏率检查通过（现有测试 `leakRate <= 0.60` 全绿）

## 2. pool 参数化评估

- [x] 2.1 `MemoryRecallEvaluator` 新增 `runPoolSensitivityAnalysis(samples, poolSizes)` 方法
- [x] 2.2 方法内部模拟 `topByPriority(candidates, poolSize)` 截断后再调 `memoryRecallService.recall`
- [x] 2.3 每个 pool 值下计算 Recall@1 / Recall@3 / MRR / Precision，记录到 `PoolSensitivityResult`
- [ ] 2.4 新增测试：pool=10 时强相关项被截断 → Recall@3 显著低于 pool=30

## 3. 敏感性分析报告

- [ ] 3.1 `MemoryEvalReportRenderer` 新增 `renderPoolSensitivity(results)` 方法
- [ ] 3.2 报告输出 pool 敏感性表格
- [ ] 3.3 报告输出结论段
- [ ] 3.4 报告标注"组 A~I 候选 < 10，pool 大小对它们无影响，敏感性分析仅基于组 J"

## 4. 验证与结论

- [x] 4.1 `mvn test` 98 个测试全绿（组 J 样本未破坏现有测试）
- [ ] 4.2 实跑 pool 敏感性分析，记录结果
- [ ] 4.3 记录结论到文档
