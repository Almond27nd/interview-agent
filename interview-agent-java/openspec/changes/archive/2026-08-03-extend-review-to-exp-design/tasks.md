## 1. 规则预检差异化

- [x] 1.1 `QuestionRuleChecker` 增加 experience 类 `context` 字段非空校验（缺陷类型 `MISSING_CONTEXT`）
- [x] 1.2 `QuestionRuleChecker` 增加 design 类 `follow_ups` 至少 2 条校验
- [ ] 1.3 新增测试：experience 方向缺少 context → 规则层报缺陷
- [ ] 1.4 新增测试：design 方向只有 1 条追问 → 规则层报缺陷
- [ ] 1.5 新增测试：basic 方向不受新增规则影响

## 2. 审题 Agent 差异化

- [x] 2.1 `QuestionReviewer` instruction 增加按 type 分化的审查维度段落：experience 类增加"简历真实性"维度，design 类增加"架构取舍"维度
- [x] 2.2 审题 instruction 明确：experience 类题目必须基于简历真实内容，审题时对照 context 字段判断
- [x] 2.3 审题 instruction 明确：design 类追问必须考察架构取舍（为什么选 A 不选 B），不接受 API 用法类追问
- [x] 2.4 `buildReviewPayload` 增加 type 和 context 字段传给 Critic

## 3. 出题路径泛化

- [x] 3.1 `assembleNode` 支持无 ReactAgent 模式（assembler 为 null 时走单轮 chatModel.call）
- [x] 3.2 新增 `assembleExpDesignWithReview` 方法（无工具 + 审题回环子图）
- [x] 3.3 `Orchestrator` 分组逻辑改为按 type+difficulty 分组，basic 走带工具路径，experience/design 走无工具路径
- [x] 3.4 确认 `Orchestrator` 调用的签名和返回值不变

## 4. 降级路径

- [x] 4.1 experience/design 方向在审题子图异常时返回空 map，Orchestrator 自动降级为 LLM 兜底
- [ ] 4.2 新增测试：审题 Agent 异常 → experience/design 方向 fail-open 采纳当前草稿

## 5. 验证

- [ ] 5.1 跑一场面试，确认 experience/design 方向的日志出现"第 N 轮审题"
- [ ] 5.2 确认 experience 方向的题目确实基于简历内容（人工抽查）
- [ ] 5.3 确认 design 方向的追问包含架构取舍类问题（人工抽查）
- [x] 5.4 `mvn test` 98 个测试全绿
- [ ] 5.5 确认降级路径：临时让 QuestionReviewer.review 抛异常 → experience/design 正常出题 + 日志 fail-open
