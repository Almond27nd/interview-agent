# AGENTS.md — 项目免疫系统

> 本文件不是"说明文档"，而是**约束库**：每一行对应一个真实犯过的错误或刻意的取舍。
> 核心原则来自 harness engineering："Anytime you find an agent makes a mistake,
> you take the time to engineer a solution such that the agent never makes that mistake again."
>
> 本文件只做**目录表**（~100 行），细节在各子包的 `AGENTS.md` 和 `docs/decisions/` 的 ADR 里。

---

## 分层依赖（机械化执行，见 `ArchitectureTest`）

```
L5  handler, auth          传输层 / 认证
L4  graph                  流程编排（Orchestrator / StageScheduler / QuestionPool）
L3  agent, skill           Agent 实现 / Skill 技能系统
L2  memory, loader, mcp    记忆 / 文档加载 / 工具
L1  rag, config            检索 / 配置
L0  model                  纯领域模型（零内部依赖）
```

**依赖只能向下，不能向上。** 违反即 CI 红。

已知的豁免与待治理项见 `docs/decisions/005-config装配豁免.md`。

---

## 跨类结构性不变量（机械化执行，见 `HarnessInvariantsTest`）

| 不变量 | 守护的决策 | 后果（若被破坏） |
|---|---|---|
| `WEAK_POINT_CANDIDATE_POOL > FUSE_TOP_K` | M4 层次修正 | RRF 退化为 N 进 N，筛选静默失效 |
| `DUPLICATE_THRESHOLD > 0.5` | 规则层零误判边界 | 同义换序被误判，合格题白白重出 |
| `MERGE_THRESHOLD > 0.50` | 实体归一不误并 | `MySQL索引` 与 `MySQL事务` 被永久合并 |
| 所有回环轮次 ∈ [1,3] | 防 token 爆炸 | 无限回炉 |
| priority 修正项之和 0.85 < 1.0 | 主信号占主导 | 修正项越权，排序退化 |

---

## 五条不可让渡给模型的事

1. **回环的「是否发生、发生几次、结论怎么解析」由代码硬保证** — 不交给模型自觉
2. **检索权只归出题者，判断权只归审题者** — 权限不对称是设计前提
3. **审题是质量增强不是准入门槛** — 全程 fail-open，Critic 挂了不能让面试没题
4. **source 字段必须诚实** — 改编题干必须改 `source=llm`，否则参考答案对不上
5. **长期记忆永不物理删除** — 达标只打软失效标记，否则复发检测结构上不可能

---

## 子包约束（详见各包 AGENTS.md）

- [`agent/AGENTS.md`](src/main/java/com/interview/agent/agent/AGENTS.md) — 阈值红线、不可让渡项、输出契约
- [`memory/AGENTS.md`](src/main/java/com/interview/agent/memory/AGENTS.md) — 阈值红线、禁止操作、已知取舍
- [`rag/AGENTS.md`](src/main/java/com/interview/agent/rag/AGENTS.md) — 检索管道约束

## 被否决的方案（ADR，防止后人"改回去"）

- [`docs/decisions/001-否决Retriever+Generator拆法.md`](docs/decisions/001-否决Retriever+Generator拆法.md)
- [`docs/decisions/002-否决Supervisor父子Agent机制.md`](docs/decisions/002-否决Supervisor父子Agent机制.md)
- [`docs/decisions/003-不把薄弱点写进Milvus.md`](docs/decisions/003-不把薄弱点写进Milvus.md)
- [`docs/decisions/004-配额校验不做成ReAct.md`](docs/decisions/004-配额校验不做成ReAct.md)
- [`docs/decisions/005-config装配豁免.md`](docs/decisions/005-config装配豁免.md)

---

## 常见错误对照（每条 = 一个真实事故）

| 症状 | 根因 | 免疫措施 |
|---|---|---|
| 指标突然飙升（Recall 0.62→0.81） | 同义词表从评估集抄词（泄漏 53.8%） | `memory/AGENTS.md` 禁止操作第 1 条 + `LeakageReport` 测试 |
| 审题总说"质量不高" | Critic 输出空话无法回喂 | `agent/AGENTS.md` 输出契约第 3 条 |
| 同一道题被反复打回 | Critic 换角度挑刺，轮次耗尽 | `MAX_REVIEW_ROUNDS=2` 硬保证（不可调高） |
| 候选人越答越崩 | 配额缺失静默化，fallback 喂 hard 题 | `DirectionQuotaChecker` 逐格校验 + `fellBack()` 可观测 |
| 薄弱点"学会了又忘"检测不到 | 物理删除使复发在结构上不可能 | 软失效 `masteredAt` + `relapseCount`（不可改回删除） |
