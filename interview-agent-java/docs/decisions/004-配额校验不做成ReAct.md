# ADR-004：配额校验不做成 ReAct

> **状态**：已决策，勿改回去
> **日期**：M5 落地期间
> **关联**：`agent/DirectionQuotaChecker.java`、`agent/QuestionPlanner.java`

## 背景

Phase 1 出方向的 9 格配额校验是一个 generate-validate-repair 循环。
自然想到的做法是做成 ReAct Agent，让模型自主校验和补全。

## 决策

**不做成 ReAct。用纯函数 + 硬上限代替。**

## 理由（三条硬理由）

1. **没有工具可调** — ReAct 的价值在于用外部观测修正下一步动作。
   出方向只依赖 JD + 简历 + 薄弱点，全部已在 context 里，没有任何需要外部查询的东西。

2. **配额是机械可判定的** — `diff()` 是纯计数，零 token、零误判。
   让一个**数不准数的模型**去校验自己数不准的结果，**逻辑上不成立**。

3. **可复现性** — 纯函数 + 硬上限 ⇒ 只要模型首轮输出相同，后续处理完全确定。

## 三种闭环范式并存（差别在控制流归属）

| 范式 | 位置 | 判定者 | 循环上限 | 有工具 |
|---|---|---|---|---|
| generate-validate-repair | Phase1 配额 | `diff()` 纯函数 | 代码 1 轮 | ❌ |
| Reflexion / Multi-Agent Critic | Phase2 审题 | 规则 + Critic LLM | 代码 2 轮 | Critic 无 |
| **ReAct** | Phase2 出题、ReviewPlanner | **模型自己** | **模型自己** | ✅ |

## 核心原则

**机械可判定的用规则，需要理解语义的才交给模型。**
配额是纯计数，属于前者。
