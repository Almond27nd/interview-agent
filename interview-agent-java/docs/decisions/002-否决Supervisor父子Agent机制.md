# ADR-002：否决 Supervisor 父子 Agent 机制

> **状态**：已否决，勿改回去
> **日期**：B4 落地期间
> **关联**：`agent/QuestionPlanner.java`、`agent/QuestionReviewer.java`

## 背景

Spring AI Alibaba Graph 框架支持两种父子 Agent 机制：
1. `ReactAgent$AgentSubGraphNode` — Agent 当子图节点
2. `AgentTool$AgentToolExecutor` — Agent 包装成 `ToolCallback`（即 Supervisor 模式）

用 Supervisor 模式可以让出题 Agent 自主决定"要不要调审题 Agent"。

## 决策

**否决。审题的触发权收归代码，不给出题 Agent。**

## 理由（三条）

1. **又回到"自己决定要不要被审"** — 调不调 Critic 由出题 Agent 判断，
   它完全可以不调，正好绕开了要解决的确认偏差

2. **审核结论会退化成自然语言** — 被父 Agent 自由解读；
   现在是代码解析成结构化 `QuestionDefect`，再由代码决定重出哪些方向

3. **轮次不可控** — `MAX_REVIEW_ROUNDS=2` 交给模型自觉遵守不可靠，
   写在条件边里是硬保证

## 核心原则

**审核的「是否发生、发生几次、结论怎么解析」三件事收归代码硬保证；
只把「检索什么、怎么出题、判不判合格」这类真正需要智能的判断交给模型。**
