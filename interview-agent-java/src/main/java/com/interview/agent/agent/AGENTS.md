# agent 包约束

## 阈值红线（勿调低/勿调高）

- **`QuestionRuleChecker.DUPLICATE_THRESHOLD = 0.6`**
  同义换序（"索引失效的场景有哪些" vs "哪些场景会导致索引失效"）实测 bigram Jaccard 仅 0.5。
  **规则层故意不报，留给 Critic**。测试 `check_reorderedDuplicate_leftToCritic` 已固化此边界。
  看到"明显重复没被规则层拦下"时，先确认是否属于语义重复，**不要下调阈值**。
  调低能抓住它，但会误伤考点相邻但确实不同的题，而误判代价比漏判更高。

- **`QuestionRuleChecker.MIN_CONTENT_LENGTH = 8`**
  短于此长度基本是模型输出被截断，不可能是一道完整的面试题。

## 不可让渡给模型的三件事

审核的「是否发生、发生几次、结论怎么解析」必须由代码硬保证：

1. **`MAX_REVIEW_ROUNDS = 2`** 写在条件边里，不交给模型自觉
   - LLM 做 Critic 时"总能再挑出点毛病"，不截断会无限回炉、token 爆炸
   - 与 `ReviewPlanner.MAX_REFLECT_ROUNDS` 保持一致
2. **审题是否触发不由出题 Agent 决定**
   - 这是**刻意不用**框架 Supervisor 父子机制的原因（见 ADR-002）
   - 否则"调不调审核"由出题 Agent 判断，它完全可以不调，绕开确认偏差
3. **Critic 结论必须解析成结构化 `QuestionDefect`**
   - 不接受自然语言自由解读（代码无法确定模型说的"第三道题"是哪一道）
   - `direction_index` 是无歧义的 join key

## 输出契约

1. **`source` 诚实性底线**：改编了题干**必须**把 source 改成 `llm`
   - 否则 `Orchestrator.buildWeakReviewContent` 按 source 回题库取的参考答案与题目不匹配
   - 这是引入审题回环后新出现的风险点

2. **Critic 禁止输出空话**
   - 禁止"质量不高""建议优化""可以改进"这类无法回喂的表述
   - 每条 reject 必须包含：具体哪里不合格 + 改进方向
   - 一道题有多个问题合并成一条，不拆成多条

3. **只回炉被打回的方向**，已 pass 的不动
   - 省 token、省检索调用
   - 避免重出时把好题改坏

## 配额校验（DirectionQuotaChecker）

- **配额表是唯一事实源**：`QUOTA` 定义配额，Prompt 文案由 `describeQuota()` 生成
  - 改配额只需动 `QUOTA`，不要去改 Prompt 里的数字（曾手写 5 处，漏一处就自相矛盾）

- **`MAX_QUOTA_FILL_ROUNDS = 1`**，且补全 prompt 明确授权"宁可少给，不许编"
  - 补不上通常不是模型不努力，而是输入里真没料（实习生简历撑不起 12 个 experience 方向）
  - 再问 5 轮只会让它编，编造的方向会让面试官拿着不存在的项目去问

- **去重复用 `MemoryWriteGate.canonicalize`**（传空集合，只要同义词表映射，不要 bigram）
  - 这是同义词表的第三个使用场景（写入侧、召回侧、方向去重）

## 两种容错策略（刻意相反，依据是"后果是否可逆"）

| 策略 | 适用 | 理由 |
|---|---|---|
| **严格拦截** | `MemoryWriteGate.acceptAsEvidence` 超时伪证据 | 污染长期记忆，跨会话**不可逆** |
| **fail-open 降级** | 审题 / 配额 / 语义通道 / ReactAgent | 只影响本场，**可逆** |

fail-open ≠ 有人兜底，而是"明知有损失但选择继续，并留下证据"。
所以重点不是那个 `if`，而是 warn 日志 + 修掉 `Picked.difficulty` 的语义错误。
没有可观测性，fail-open 就退化成"掩盖问题"。
