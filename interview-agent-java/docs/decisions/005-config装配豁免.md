# ADR-005：config 包装配豁免

> **状态**：已知豁免，ArchUnit 规则里显式排除
> **日期**：H3 落地期间
> **关联**：`config/SkillConfig.java`、`config/WebSocketConfig.java`、`ArchitectureTest.java`

## 背景

ArchUnit 架构测试要求"依赖只能向下"。但 `config` 包里有两个类引用了上层包：

- `SkillConfig.java` → `skill` 包
- `WebSocketConfig.java` → `handler` 包

## 决策

**豁免。** `config` 包在 ArchUnit 规则里不作为"底层"约束，允许它引用上层包做 Spring 装配。

## 理由

这是 Spring `@Configuration` 手工装配的常见现象——配置类天然需要"知道"所有要装配的 Bean。
这不是逻辑倒置，而是框架的装配机制。强行消除会让配置代码变得不自然。

## 待治理项（非本次范围）

以下两处逆向边是**真问题**，但治理成本较高，留待后续：

1. **`loader/WebLoader.java` → `mcp.WebScraperTool`**
   `WebScraperTool` 同时是 HTTP 客户端和 ToolCallback，职责混淆。
   建议：抽 `HttpFetcher` 到 `loader`，`mcp` 只做包装。

2. **`agent/DirectionQuotaChecker.java` → `memory.MemoryWriteGate`**
   topic 归一化算法被两层共用。
   建议：抽到 `common/text/TopicNormalizer`。
