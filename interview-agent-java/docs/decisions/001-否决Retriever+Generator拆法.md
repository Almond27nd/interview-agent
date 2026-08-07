# ADR-001：否决 Retriever + Generator 拆法

> **状态**：已否决，勿改回去
> **日期**：B4 落地期间
> **关联**：`agent/QuestionPlanner.java` Phase 2 出题-审题子图

## 背景

B1（Agentic RAG 改造）之后，出题环节仍是 Single-Agent。
自然想到的"教科书"拆法是 **Retriever + Generator**：
- Retriever 负责检索题库
- Generator 负责基于检索结果生成题目

## 决策

**否决。采用 Assembler + Critic 代替。**

## 理由

题库检索命中时指令要求"原题照搬不得改编"，Generator 拿到 evidence 后几乎没有生成空间，
只剩补追问、整理参考答案这类格式化工作。

一档 5 个方向、3 个命中的典型情况下，**两个 Agent 职责重叠在"出题"上**，说不清第二个 Agent 的价值。

| | Retriever + Generator | Assembler + Critic（采用） |
|---|---|---|
| 职责重叠 | 有（题库命中时近乎透传） | 无（生成 vs 批判，天然对立） |
| 回环 | 单向流水线 | 天然双向 |
| 现有代码改动 | 大（现有 Agent 拆两半、instruction 重写） | 小（现有 Agent 完全不动） |
| 实际质量收益 | 不明显 | 明显 |
| 论文背书 | RAG 两段结构 | Reflexion / Constitutional AI / Actor-Critic |

## 核心原则

**生成和批判是天然对立的立场，职责零重叠且天然有回环。**
当两个角色的职责有重叠时，说不清第二个角色存在的价值。
