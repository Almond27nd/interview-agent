/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.memory.eval;

import java.util.List;

/**
 * 记忆召回评估的标注数据集（60 条）。
 *
 * <p><b>标注原则</b>（决定 {@code relevantTopics} 该填什么）：
 * <ol>
 *   <li><b>与本场 JD 技术栈同属一个考点</b>——包括同义、缩写、上下位、实现框架与协议的关系。
 *       例如 JD 写 Seata，则「分布式事务」相关；JD 写 epoll，则「IO模型」相关。</li>
 *   <li><b>候选人确实没掌握</b>——已经答得很好的点不该重复考（掌握度高的不标）。</li>
 *   <li><b>跨岗位共性问题按实际相关性判断</b>——「系统设计」对多数后端岗位都相关，
 *       但「Flink窗口」对纯 Java Web 岗位不相关。</li>
 *   <li><b>标注只依据「本场该不该重点考」这一客观判断，不参考任一策略的实际行为。</b>
 *       这条是防止评估失真的关键——见下方「反自证」说明。</li>
 * </ol>
 *
 * <p><b>⚠️ 反自证设计（本数据集最重要的性质）</b>：
 * 第一版数据集只有 8 条、且全部是「我知道混合召回能解决的场景」，跑出了 Recall@3 = 1.0000 的满分——
 * 这等于自己出题自己答，数字毫无意义。因此本数据集刻意按下列配比构造，
 * 使<b>相当比例的样本对 hybrid 不利或两策略均无解</b>：
 * <table border="1">
 *   <tr><th>类别</th><th>作用</th></tr>
 *   <tr><td>hybrid 应当明显更优</td><td>验证改造的正向收益</td></tr>
 *   <tr><td>两策略应当持平</td><td>验证改造没有把原本能召回的搞丢（防退化）</td></tr>
 *   <tr><td><b>hybrid 应当更差</b></td><td>暴露词法通道「共享英文词导致假阳性」与语义通道「泛化词带偏」的真实缺陷</td></tr>
 *   <tr><td><b>两策略均应失败</b></td><td>暴露能力上限（如全部已掌握属 M1 筛选层职责，M3 无法解决）</td></tr>
 * </table>
 * 若某次改动让「应当更差」「均应失败」这两类样本突然变好，首先要怀疑的是标注被污染或指标口径出错，
 * 而不是策略变强了。
 *
 * <p><b>分组结构</b>：
 * <table border="1">
 *   <tr><th>组</th><th>样本</th><th>用途</th></tr>
 *   <tr><td>A</td><td>001~008</td><td>基础场景：同义漏召回、字面命中防退化、排序质量</td></tr>
 *   <tr><td>B</td><td>009~012</td><td>困难样本：表外新技术、字面相似考点不同、全无关、已掌握</td></tr>
 *   <tr><td>C</td><td>013~017</td><td>岗位多样性：大数据/前端/运维/Android/算法</td></tr>
 *   <tr><td>D</td><td>018~022</td><td>语义关系类型：缩写、框架↔概念、变体区分、中英混写、上下位</td></tr>
 *   <tr><td>E</td><td>023~028</td><td>更多岗位 + Precision 专项</td></tr>
 *   <tr><td>F</td><td>029~034</td><td>网络/架构 + 滥召回检验</td></tr>
 *   <tr><td>G</td><td>035~040</td><td>语义必需场景 + 假阳性强化</td></tr>
 *   <tr><td><b>H</b></td><td>041~050</td><td><b>语义通道专项</b>：字面零交集，用于量化 embedding 的独立贡献</td></tr>
 *   <tr><td><b>I</b></td><td>051~060</td><td><b>语义通道风险专项</b>：相近但不同、泛化词陷阱、OLAP/OLTP 区分</td></tr>
 * </table>
 *
 * <p><b>覆盖维度</b>：
 * <ul>
 *   <li><b>岗位（11 种）</b>：Java后端 / Go后端 / C++后端 / Python后端 / 前端 / Android /
 *       大数据 / 算法 / 测试开发 / 运维SRE / 数据分析——避免只在 Java 语境下评估；</li>
 *   <li><b>语义关系类型</b>：同义（Seata↔分布式事务）、缩写（JMM↔JVM内存模型、AQS↔Java并发）、
 *       上下位（epoll↔IO模型）、框架与概念（Spring AOP↔动态代理、RAII↔智能指针）、
 *       业务化表述（订单超时关闭↔延迟任务实现）、中英混写、版本/变体区分；</li>
 *   <li><b>反例类型</b>：跨技术栈不相关、字面相似但考点不同、共享英文前缀、
 *       泛化词陷阱（前端性能优化 vs 接口性能优化）、跨语言同概念（Go GC vs JVM GC）、
 *       OLAP/OLTP 混淆、全部无关、全部已掌握。</li>
 * </ul>
 */
public final class MemoryEvalDatasetTemplate {

    private MemoryEvalDatasetTemplate() {
    }

    private static MemoryEvalSample.CandidateSpec c(String topic, double score, int hit, int wrong,
                                                    boolean stubborn, int daysAgo, String difficulty) {
        return MemoryEvalSample.CandidateSpec.builder()
                .topic(topic).score(score).hitCount(hit).wrongCount(wrong)
                .stubborn(stubborn).daysAgo(daysAgo).difficulty(difficulty)
                .build();
    }

    public static List<MemoryEvalSample> build() {
        return List.of(

                // ═══════════════════════════════════════════════════════════
                // 组 A（mem_001 ~ mem_008）：基础场景
                // ═══════════════════════════════════════════════════════════

                // ① 核心场景：同义词漏召回。JD 用「Seata / 两阶段提交」，薄弱点叫「分布式事务」
                MemoryEvalSample.builder()
                        .id("mem_001")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("seata", "两阶段提交", "spring cloud", "微服务"))
                        .candidates(List.of(
                                c("分布式事务", 55, 3, 3, false, 10, "medium"),
                                c("微服务", 60, 2, 1, false, 8, "medium"),
                                c("Flink窗口", 52, 2, 2, false, 25, "medium"),
                                c("Go并发", 58, 2, 2, false, 40, "medium")))
                        .relevantTopics(List.of("分布式事务", "微服务"))
                        .note("Seata 是分布式事务框架、两阶段提交是其协议，但字符串互不包含——baseline 必漏")
                        .build(),

                // ② 字面命中场景：验证 hybrid 没有把原本能召回的搞丢
                MemoryEvalSample.builder()
                        .id("mem_002")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("mysql", "redis", "java并发"))
                        .candidates(List.of(
                                c("MySQL索引", 55, 4, 4, false, 6, "medium"),
                                c("Redis持久化", 58, 3, 3, false, 12, "medium"),
                                c("Java并发", 52, 5, 4, true, 4, "hard"),
                                c("Flink窗口", 50, 1, 1, false, 30, "medium")))
                        .relevantTopics(List.of("Java并发", "MySQL索引", "Redis持久化"))
                        .note("三者字面均命中；Java并发 是顽固薄弱点应排最前")
                        .build(),

                // ③ 排序质量：两者都相关，但顽固的应排前面
                MemoryEvalSample.builder()
                        .id("mem_003")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("mysql"))
                        .candidates(List.of(
                                c("MySQL事务", 58, 1, 1, false, 3, "medium"),
                                c("MySQL索引", 55, 6, 5, true, 5, "hard")))
                        .relevantTopics(List.of("MySQL索引", "MySQL事务"))
                        .note("MySQL索引 考6错5且顽固，应排第一；baseline 按最新得分排会把 MySQL事务 排前")
                        .build(),

                // ④ 上下位概念：JD 说 epoll，薄弱点是「IO模型」
                MemoryEvalSample.builder()
                        .id("mem_004")
                        .position("C++后端开发工程师")
                        .jdSkills(List.of("epoll", "io多路复用", "tcp"))
                        .candidates(List.of(
                                c("IO模型", 52, 3, 3, false, 9, "hard"),
                                c("TCP协议", 60, 2, 1, false, 15, "medium"),
                                c("MySQL索引", 58, 2, 2, false, 35, "medium")))
                        .relevantTopics(List.of("IO模型", "TCP协议"))
                        .note("epoll 是 IO 多路复用的具体实现，属上下位关系")
                        .build(),

                // ⑤ 不误召回：Go 岗位不该把 JVM 相关薄弱点判为强相关
                MemoryEvalSample.builder()
                        .id("mem_005")
                        .position("Go后端开发工程师")
                        .jdSkills(List.of("goroutine", "channel", "go调度器"))
                        .candidates(List.of(
                                c("Go并发", 55, 4, 4, true, 7, "hard"),
                                c("Go channel", 58, 2, 2, false, 11, "medium"),
                                c("JVM垃圾回收", 50, 3, 3, true, 20, "hard")))
                        .relevantTopics(List.of("Go并发", "Go channel"))
                        .note("JVM垃圾回收 虽是顽固薄弱点但与 Go 岗位无关，不应判为强相关——检验 Precision")
                        .build(),

                // ⑥ 别名兜底：aliases 里存了 JD 的用词，即使语义通道降级也应命中
                MemoryEvalSample.builder()
                        .id("mem_006")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("tcc", "最终一致性"))
                        .candidates(List.of(
                                MemoryEvalSample.CandidateSpec.builder()
                                        .topic("分布式事务").score(52).hitCount(4).wrongCount(4)
                                        .stubborn(true).daysAgo(6).difficulty("hard")
                                        .aliases(List.of("TCC", "最终一致性"))
                                        .build(),
                                c("MySQL锁", 60, 2, 1, false, 18, "medium")))
                        .relevantTopics(List.of("分布式事务"))
                        .note("实体归一时留档的别名参与词法匹配，是 M2 的正向副产物")
                        .build(),

                // ⑦ 缓存类同义
                MemoryEvalSample.builder()
                        .id("mem_007")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("缓存穿透", "缓存雪崩", "高并发"))
                        .candidates(List.of(
                                c("Redis缓存问题", 50, 3, 3, true, 8, "hard"),
                                c("Redis数据结构", 62, 2, 1, false, 14, "easy"),
                                c("操作系统内存管理", 55, 2, 2, false, 28, "medium")))
                        .relevantTopics(List.of("Redis缓存问题"))
                        .note("缓存穿透/雪崩 与「Redis缓存问题」是同一考点的不同表述；"
                                + "「操作系统内存管理」仅共有一个『存』字，不应召回（曾因此产生假阳性）")
                        .build(),

                // ⑧ 混合场景：部分字面命中 + 部分需语义
                MemoryEvalSample.builder()
                        .id("mem_008")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("kafka", "消息幂等", "mysql"))
                        .candidates(List.of(
                                c("消息队列可靠性", 52, 3, 3, false, 10, "hard"),
                                c("Kafka原理", 58, 2, 2, false, 13, "medium"),
                                c("MySQL索引", 55, 5, 4, true, 5, "medium"),
                                c("Go并发", 60, 1, 1, false, 45, "easy")))
                        .relevantTopics(List.of("MySQL索引", "Kafka原理", "消息队列可靠性"))
                        .note("Kafka/MySQL 字面命中；「消息幂等」↔「消息队列可靠性」需语义")
                        .build(),

                // ═══════════════════════════════════════════════════════════
                // 组 B（mem_009 ~ mem_012）：困难样本 —— 刻意构造成对 hybrid 不利
                // ═══════════════════════════════════════════════════════════

                // ⑨ 同义词表未覆盖的新技术点：Saga / XA 不在同义词表里，降级状态下 hybrid 也应漏
                MemoryEvalSample.builder()
                        .id("mem_009")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("saga模式", "xa协议"))
                        .candidates(List.of(
                                c("分布式事务", 52, 4, 4, true, 7, "hard"),
                                c("MySQL锁", 60, 2, 1, false, 20, "medium"),
                                c("Java集合", 65, 2, 1, false, 30, "easy")))
                        .relevantTopics(List.of("分布式事务"))
                        .note("困难样本：Saga/XA 未收录进同义词表，仅靠词法必漏；只有语义通道生效时才能召回")
                        .build(),

                // ⑩ 字面相似但考点不同
                MemoryEvalSample.builder()
                        .id("mem_010")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("jvm内存模型", "java并发"))
                        .candidates(List.of(
                                c("JVM内存模型", 55, 3, 3, false, 8, "hard"),
                                c("操作系统内存管理", 58, 2, 2, false, 22, "medium"),
                                c("Java并发", 52, 4, 3, true, 6, "hard")))
                        .relevantTopics(List.of("JVM内存模型", "Java并发"))
                        .note("困难样本：「操作系统内存管理」与「JVM内存模型」共有『内存』两字但考点不同，不应召回")
                        .build(),

                // ⑪ 全部候选都与岗位无关：正确行为是一条都不判强相关
                MemoryEvalSample.builder()
                        .id("mem_011")
                        .position("前端开发工程师")
                        .jdSkills(List.of("react", "typescript", "webpack"))
                        .candidates(List.of(
                                c("MySQL索引", 50, 5, 5, true, 5, "hard"),
                                c("JVM垃圾回收", 52, 4, 4, true, 9, "hard"),
                                c("Go并发", 55, 3, 3, false, 14, "medium")))
                        .relevantTopics(List.of())
                        .note("困难样本：候选全为后端薄弱点、岗位是前端，正确行为是全部判为『供参考』而非强相关")
                        .build(),

                // ⑫ 掌握度高的点不该被重复考
                MemoryEvalSample.builder()
                        .id("mem_012")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("mysql", "redis"))
                        .candidates(List.of(
                                c("MySQL索引", 88, 4, 1, false, 4, "hard"),
                                c("Redis持久化", 50, 3, 3, true, 6, "hard")))
                        .relevantTopics(List.of("Redis持久化"))
                        .note("困难样本：MySQL索引 近期 hard 题得 88 分已基本掌握，不该与真薄弱点并列重点考；"
                                + "这是 M1 筛选层的职责，M3 单独无法解决——属已知上限")
                        .build(),

                // ═══════════════════════════════════════════════════════════
                // 组 C（mem_013 ~ mem_017）：岗位多样性 —— 避免只在 Java 语境下评估
                // ═══════════════════════════════════════════════════════════

                // ⑬ 大数据开发：三者字面均命中，检验不退化
                MemoryEvalSample.builder()
                        .id("mem_013")
                        .position("大数据开发工程师")
                        .jdSkills(List.of("flink", "spark", "hive", "数据倾斜"))
                        .candidates(List.of(
                                c("Flink窗口", 52, 3, 3, true, 8, "hard"),
                                c("数据倾斜", 55, 2, 2, false, 12, "medium"),
                                c("Spark调优", 58, 2, 1, false, 20, "medium"),
                                c("MySQL索引", 60, 2, 1, false, 40, "easy")))
                        .relevantTopics(List.of("Flink窗口", "数据倾斜", "Spark调优"))
                        .note("公平样本：三者字面均命中，两策略应持平——用于检验 hybrid 没有把原本能召回的搞丢")
                        .build(),

                // ⑭ 前端：真实前端薄弱点，字面命中
                MemoryEvalSample.builder()
                        .id("mem_014")
                        .position("前端开发工程师")
                        .jdSkills(List.of("react", "虚拟dom", "webpack", "浏览器渲染"))
                        .candidates(List.of(
                                c("React Hooks", 52, 3, 3, true, 7, "hard"),
                                c("虚拟DOM", 55, 2, 2, false, 14, "medium"),
                                c("浏览器渲染原理", 58, 2, 2, false, 21, "medium"),
                                c("MySQL索引", 60, 1, 1, false, 50, "easy")))
                        .relevantTopics(List.of("React Hooks", "虚拟DOM", "浏览器渲染原理"))
                        .note("公平样本：验证归一化（大小写/空格）后字面匹配在前端语境同样有效")
                        .build(),

                // ⑮ 运维 SRE：Docker ↔ 容器隔离原理 需语义
                MemoryEvalSample.builder()
                        .id("mem_015")
                        .position("运维开发工程师")
                        .jdSkills(List.of("kubernetes", "docker", "prometheus"))
                        .candidates(List.of(
                                c("Kubernetes调度", 52, 3, 3, true, 9, "hard"),
                                c("容器隔离原理", 56, 2, 2, false, 18, "medium"),
                                c("Java并发", 62, 1, 1, false, 60, "easy")))
                        .relevantTopics(List.of("Kubernetes调度", "容器隔离原理"))
                        .note("困难样本：Docker 的核心考点就是容器隔离（namespace/cgroup），"
                                + "但字面无交集，降级状态下 hybrid 也应漏")
                        .build(),

                // ⑯ Android
                MemoryEvalSample.builder()
                        .id("mem_016")
                        .position("Android开发工程师")
                        .jdSkills(List.of("kotlin", "jetpack compose", "内存泄漏"))
                        .candidates(List.of(
                                c("Kotlin协程", 54, 3, 3, false, 10, "hard"),
                                c("Android内存泄漏", 50, 4, 4, true, 6, "hard"),
                                c("JVM垃圾回收", 58, 2, 2, false, 25, "medium")))
                        .relevantTopics(List.of("Android内存泄漏", "Kotlin协程"))
                        .note("JVM垃圾回收 不标：Android 运行时是 ART 而非 HotSpot，属不同考点——避免过度关联")
                        .build(),

                // ⑰ 算法 / 机器学习
                MemoryEvalSample.builder()
                        .id("mem_017")
                        .position("算法工程师")
                        .jdSkills(List.of("pytorch", "transformer", "注意力机制"))
                        .candidates(List.of(
                                c("Transformer结构", 52, 3, 3, false, 11, "hard"),
                                c("注意力机制", 55, 2, 2, false, 13, "medium"),
                                c("梯度消失", 58, 2, 2, false, 26, "medium"),
                                c("MySQL索引", 62, 1, 1, false, 55, "easy")))
                        .relevantTopics(List.of("Transformer结构", "注意力机制", "梯度消失"))
                        .note("梯度消失 标为相关：残差连接正是为解决深层网络梯度问题，属该岗必考基础；字面无交集")
                        .build(),

                // ═══════════════════════════════════════════════════════════
                // 组 D（mem_018 ~ mem_022）：语义关系类型多样性
                // ═══════════════════════════════════════════════════════════

                // ⑱ 缩写 ↔ 全称：JMM / volatile / CAS
                MemoryEvalSample.builder()
                        .id("mem_018")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("jmm", "volatile", "cas"))
                        .candidates(List.of(
                                c("JVM内存模型", 52, 3, 3, true, 8, "hard"),
                                c("Java并发", 55, 4, 3, false, 10, "hard"),
                                c("Redis持久化", 60, 2, 1, false, 30, "easy")))
                        .relevantTopics(List.of("JVM内存模型", "Java并发"))
                        .note("困难样本：JMM 即 JVM 内存模型、volatile/CAS 属 Java 并发，"
                                + "但三个缩写与 topic 字面完全无交集——两策略均应失败，纯语义样本")
                        .build(),

                // ⑲ 框架 ↔ 底层概念：Spring AOP ↔ 动态代理
                MemoryEvalSample.builder()
                        .id("mem_019")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("spring aop", "动态代理", "ioc"))
                        .candidates(List.of(
                                c("动态代理", 54, 3, 3, false, 12, "medium"),
                                c("Spring原理", 56, 2, 2, false, 16, "medium"),
                                c("设计模式", 60, 2, 1, false, 28, "easy")))
                        .relevantTopics(List.of("动态代理", "Spring原理"))
                        .note("「Spring原理」与「spring aop」共享 ASCII 词 spring，"
                                + "词法 token 重叠可命中而 baseline 的双向 contains 不能——hybrid 应更优")
                        .build(),

                // ⑳ 变体区分（负向）：同一技术栈内不同考点
                MemoryEvalSample.builder()
                        .id("mem_020")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("redis集群", "redis哨兵"))
                        .candidates(List.of(
                                c("Redis集群模式", 52, 3, 3, false, 9, "hard"),
                                c("Redis持久化", 58, 2, 2, false, 15, "medium"),
                                c("Redis数据结构", 64, 2, 1, false, 22, "easy")))
                        .relevantTopics(List.of("Redis集群模式"))
                        .note("困难样本（hybrid 应更差）：三个候选都含 ASCII 词 redis，"
                                + "词法 token 重叠会把持久化/数据结构一并判为相关，而它们与集群/哨兵是不同考点；"
                                + "baseline 的严格 contains 反而不会误召回——这条专门暴露共享英文词的假阳性")
                        .build(),

                // ㉑ 中英混写 + 汉字交集陷阱
                MemoryEvalSample.builder()
                        .id("mem_021")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("消息队列", "rocketmq", "事务消息"))
                        .candidates(List.of(
                                c("消息队列可靠性", 52, 3, 3, true, 7, "hard"),
                                c("Kafka原理", 56, 2, 2, false, 19, "medium"),
                                c("MySQL事务", 60, 2, 1, false, 33, "easy")))
                        .relevantTopics(List.of("消息队列可靠性", "Kafka原理"))
                        .note("困难样本（hybrid 应更差）：「MySQL事务」与「事务消息」共有『事务』两字会被词法判为相关，"
                                + "但数据库事务与 MQ 事务消息是不同考点；而真正相关的「Kafka原理」字面无交集")
                        .build(),

                // ㉒ 上下位（操作系统 / 并发）
                MemoryEvalSample.builder()
                        .id("mem_022")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("进程调度", "上下文切换", "线程模型"))
                        .candidates(List.of(
                                c("操作系统进程管理", 54, 3, 3, false, 13, "medium"),
                                c("线程池", 58, 2, 2, false, 17, "medium"),
                                c("Java并发", 52, 4, 4, true, 6, "hard")))
                        .relevantTopics(List.of("操作系统进程管理", "线程池", "Java并发"))
                        .note("baseline 的 contains 三者全否；词法 token 重叠可命中前两个（『进程』『线程』）——hybrid 应更优")
                        .build(),

                // ═══════════════════════════════════════════════════════════
                // 组 E（mem_023 ~ mem_028）：更多岗位 + Precision 专项
                // ═══════════════════════════════════════════════════════════

                // ㉓ Python 后端：asyncio ↔ 协程原理 需语义
                MemoryEvalSample.builder()
                        .id("mem_023")
                        .position("Python后端开发工程师")
                        .jdSkills(List.of("python", "django", "asyncio"))
                        .candidates(List.of(
                                c("Python GIL", 52, 3, 3, true, 8, "hard"),
                                c("协程原理", 55, 2, 2, false, 14, "medium"),
                                c("Java并发", 58, 3, 2, false, 30, "medium")))
                        .relevantTopics(List.of("Python GIL", "协程原理"))
                        .note("困难样本：asyncio 就是 Python 协程，但字面无交集，降级下必漏其一")
                        .build(),

                // ㉔ 测试开发
                MemoryEvalSample.builder()
                        .id("mem_024")
                        .position("测试开发工程师")
                        .jdSkills(List.of("自动化测试", "接口测试", "python"))
                        .candidates(List.of(
                                c("接口测试设计", 54, 3, 3, false, 12, "medium"),
                                c("Python GIL", 60, 2, 1, false, 26, "easy"),
                                c("性能压测", 56, 2, 2, false, 20, "medium")))
                        .relevantTopics(List.of("接口测试设计", "性能压测", "Python GIL"))
                        .note("「性能压测」与「自动化测试」仅共有一个『测』字，按 isMeaningfulOverlap 规则不应命中——"
                                + "它属该岗核心考点却只能靠语义救回，两策略均应漏")
                        .build(),

                // ㉕ 数据分析：子串误匹配（两策略共有的缺陷）
                MemoryEvalSample.builder()
                        .id("mem_025")
                        .position("数据分析工程师")
                        .jdSkills(List.of("sql", "hive", "指标体系"))
                        .candidates(List.of(
                                c("Hive优化", 56, 2, 2, false, 15, "medium"),
                                c("NoSQL选型", 58, 2, 2, false, 18, "medium"),
                                c("指标体系设计", 54, 3, 3, true, 9, "hard"),
                                c("JVM垃圾回收", 62, 2, 2, false, 34, "medium")))
                        .relevantTopics(List.of("Hive优化", "指标体系设计"))
                        .note("「NoSQL选型」含子串 sql 会被两策略同时误召回（NoSQL 与 SQL 是相反的考点）——"
                                + "这是子串匹配的共有缺陷，非 hybrid 引入")
                        .build(),

                // ㉖ 排序质量：字面全命中但只有一个真该重点考
                MemoryEvalSample.builder()
                        .id("mem_026")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("mysql"))
                        .candidates(List.of(
                                c("MySQL锁", 50, 5, 5, true, 4, "hard"),
                                c("MySQL事务", 78, 3, 1, false, 6, "medium"),
                                c("MySQL索引", 75, 3, 1, false, 8, "medium")))
                        .relevantTopics(List.of("MySQL锁"))
                        .note("三者字面均命中，但事务/索引近期得分已高（掌握度上来了），只有 MySQL锁 是真薄弱点；"
                                + "检验掌握度置信度是否真正参与排序（MRR 应为 1.0）")
                        .build(),

                // ㉗ C++ 后端
                MemoryEvalSample.builder()
                        .id("mem_027")
                        .position("C++后端开发工程师")
                        .jdSkills(List.of("智能指针", "移动语义", "stl"))
                        .candidates(List.of(
                                c("C++智能指针", 52, 3, 3, true, 8, "hard"),
                                c("C++内存管理", 56, 2, 2, false, 16, "medium"),
                                c("STL容器", 58, 2, 1, false, 20, "medium"),
                                c("Java集合", 64, 1, 1, false, 45, "easy")))
                        .relevantTopics(List.of("C++智能指针", "STL容器", "C++内存管理"))
                        .note("移动语义 ↔ C++内存管理 属资源管理同一考点但字面无交集，需语义；另两个字面命中")
                        .build(),

                // ㉘ Go 深入：共享 ASCII 词 go 带来的正向收益
                MemoryEvalSample.builder()
                        .id("mem_028")
                        .position("Go后端开发工程师")
                        .jdSkills(List.of("gmp", "go内存逃逸", "pprof"))
                        .candidates(List.of(
                                c("Go并发", 52, 4, 4, true, 7, "hard"),
                                c("Go内存管理", 55, 3, 3, false, 13, "hard"),
                                c("Go channel", 60, 2, 1, false, 25, "medium"),
                                c("Redis持久化", 62, 1, 1, false, 40, "easy")))
                        .relevantTopics(List.of("Go并发", "Go内存管理", "Go channel"))
                        .note("baseline 的双向 contains 三者全否（gmp/pprof 与 topic 互不包含）；"
                                + "词法 token 重叠靠共享的 go 全部命中——hybrid 应明显更优")
                        .build(),

                // ═══════════════════════════════════════════════════════════
                // 组 F（mem_029 ~ mem_034）：网络 / 架构 / 滥召回检验
                // ═══════════════════════════════════════════════════════════

                // ㉙ 网络协议深入
                MemoryEvalSample.builder()
                        .id("mem_029")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("tcp拥塞控制", "https握手", "http2"))
                        .candidates(List.of(
                                c("TCP协议", 52, 4, 4, true, 9, "hard"),
                                c("HTTP协议", 56, 3, 2, false, 15, "medium"),
                                c("IO模型", 60, 2, 2, false, 28, "medium")))
                        .relevantTopics(List.of("TCP协议", "HTTP协议"))
                        .note("baseline 全否（『tcp拥塞控制』不包含『tcp协议』）；词法可靠 tcp 命中其一，"
                                + "但 https/http2 与『HTTP协议』分词后无交集——部分提升")
                        .build(),

                // ㉚ 系统设计：全部需语义
                MemoryEvalSample.builder()
                        .id("mem_030")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("高并发架构", "限流降级", "分布式缓存"))
                        .candidates(List.of(
                                c("系统设计", 52, 3, 3, true, 10, "hard"),
                                c("服务治理", 56, 2, 2, false, 18, "medium"),
                                c("Redis缓存问题", 58, 3, 2, false, 12, "medium"),
                                c("Java集合", 66, 1, 1, false, 50, "easy")))
                        .relevantTopics(List.of("系统设计", "服务治理", "Redis缓存问题"))
                        .note("困难样本：高并发架构↔系统设计、限流降级↔服务治理 均为同义改写但字面无交集；"
                                + "仅『分布式缓存』能靠『缓存』两字命中——降级下 Recall 应很低")
                        .build(),

                // ㉛ 假阳性回归检验：跨领域且仅有单字交集
                MemoryEvalSample.builder()
                        .id("mem_031")
                        .position("前端开发工程师")
                        .jdSkills(List.of("性能优化", "首屏加载"))
                        .candidates(List.of(
                                c("前端性能优化", 52, 3, 3, true, 8, "hard"),
                                c("MySQL索引", 58, 3, 3, false, 20, "medium"),
                                c("操作系统内存管理", 60, 2, 2, false, 35, "medium")))
                        .relevantTopics(List.of("前端性能优化"))
                        .note("回归检验：后两个与 JD 无任何有效交集，应保持 Precision = 1.0；"
                                + "若此条出现误召回，说明 isMeaningfulOverlap 的单字过滤失效")
                        .build(),

                // ㉜ 别名兜底第二例（不同领域）
                MemoryEvalSample.builder()
                        .id("mem_032")
                        .position("C++后端开发工程师")
                        .jdSkills(List.of("epoll", "nio"))
                        .candidates(List.of(
                                MemoryEvalSample.CandidateSpec.builder()
                                        .topic("IO模型").score(52).hitCount(4).wrongCount(4)
                                        .stubborn(true).daysAgo(9).difficulty("hard")
                                        .aliases(List.of("epoll", "NIO"))
                                        .build(),
                                c("线程池", 58, 2, 2, false, 17, "medium")))
                        .relevantTopics(List.of("IO模型"))
                        .note("baseline 看不到别名必漏；hybrid 靠 aliases 精确命中——验证 M2 留档别名的跨模块价值")
                        .build(),

                // ㉝ 复发标记参与排序
                MemoryEvalSample.builder()
                        .id("mem_033")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("mysql", "分库分表"))
                        .candidates(List.of(
                                MemoryEvalSample.CandidateSpec.builder()
                                        .topic("MySQL索引").score(56).hitCount(8).wrongCount(6)
                                        .stubborn(true).relapseCount(2).daysAgo(5).difficulty("hard")
                                        .build(),
                                c("分库分表", 82, 3, 1, false, 11, "medium"),
                                c("Redis持久化", 64, 1, 1, false, 42, "easy")))
                        .relevantTopics(List.of("MySQL索引"))
                        .note("分库分表 虽字面命中但近期得 82 分已掌握；MySQL索引 曾掌握后复发 2 次，应排首位")
                        .build(),

                // ㉞ 空黄金标准第二例：Go 岗位面对纯前端薄弱点
                MemoryEvalSample.builder()
                        .id("mem_034")
                        .position("Go后端开发工程师")
                        .jdSkills(List.of("goroutine", "grpc", "etcd"))
                        .candidates(List.of(
                                c("React Hooks", 52, 3, 3, true, 9, "hard"),
                                c("虚拟DOM", 56, 2, 2, false, 15, "medium"),
                                c("CSS布局", 60, 2, 2, false, 25, "easy")))
                        .relevantTopics(List.of())
                        .note("正确行为是一条都不判强相关；专门检验会不会为凑 Recall 而滥召回")
                        .build(),

                // ═══════════════════════════════════════════════════════════
                // 组 G（mem_035 ~ mem_040）：语义必需场景 + 假阳性强化
                // ═══════════════════════════════════════════════════════════

                // ㉟ Kafka 运维语汇 ↔ 原理考点
                MemoryEvalSample.builder()
                        .id("mem_035")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("消息积压", "消费者组", "offset提交"))
                        .candidates(List.of(
                                c("Kafka原理", 52, 3, 3, true, 8, "hard"),
                                c("消息队列可靠性", 56, 2, 2, false, 14, "hard"),
                                c("MySQL索引", 62, 1, 1, false, 38, "easy")))
                        .relevantTopics(List.of("Kafka原理", "消息队列可靠性"))
                        .note("消费者组/offset 是 Kafka 的核心概念但字面不含 kafka；"
                                + "仅『消息队列可靠性』能靠『消息』两字命中——部分召回")
                        .build(),

                // ㊱ 大数据第二例
                MemoryEvalSample.builder()
                        .id("mem_036")
                        .position("大数据开发工程师")
                        .jdSkills(List.of("shuffle优化", "数据倾斜", "yarn调度"))
                        .candidates(List.of(
                                c("Spark调优", 52, 3, 3, true, 10, "hard"),
                                c("数据倾斜", 55, 2, 2, false, 12, "hard"),
                                c("Flink窗口", 60, 2, 1, false, 30, "medium")))
                        .relevantTopics(List.of("Spark调优", "数据倾斜"))
                        .note("shuffle/yarn 与「Spark调优」仅单字『优』『调』交集，按规则不命中——两策略应持平")
                        .build(),

                // ㊲ 算法第二例
                MemoryEvalSample.builder()
                        .id("mem_037")
                        .position("算法工程师")
                        .jdSkills(List.of("bert", "微调", "loss设计"))
                        .candidates(List.of(
                                c("Transformer结构", 52, 3, 3, true, 9, "hard"),
                                c("模型微调", 56, 2, 2, false, 14, "medium"),
                                c("梯度消失", 60, 2, 2, false, 24, "medium")))
                        .relevantTopics(List.of("Transformer结构", "模型微调"))
                        .note("BERT 基于 Transformer 但字面无交集，属典型缩写/派生关系——需语义")
                        .build(),

                // ㊳ 运维第二例：全部需语义
                MemoryEvalSample.builder()
                        .id("mem_038")
                        .position("运维开发工程师")
                        .jdSkills(List.of("日志采集", "elk", "监控告警"))
                        .candidates(List.of(
                                c("可观测性建设", 52, 3, 3, true, 11, "hard"),
                                c("Kubernetes调度", 58, 2, 2, false, 19, "medium"),
                                c("MySQL索引", 64, 1, 1, false, 44, "easy")))
                        .relevantTopics(List.of("可观测性建设"))
                        .note("困难样本：日志/监控告警 正是可观测性的组成部分，属上位概念归纳，"
                                + "字面完全无交集——两策略均应失败")
                        .build(),

                // ㊴ 综合场景：字面 + 语义 + 干扰项
                MemoryEvalSample.builder()
                        .id("mem_039")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("redis", "分布式锁", "秒杀"))
                        .candidates(List.of(
                                c("Redis持久化", 56, 2, 2, false, 16, "medium"),
                                c("分布式锁", 50, 4, 4, true, 6, "hard"),
                                c("高并发库存扣减", 54, 2, 2, false, 13, "hard"),
                                c("Java集合", 66, 1, 1, false, 48, "easy")))
                        .relevantTopics(List.of("分布式锁", "高并发库存扣减", "Redis持久化"))
                        .note("秒杀 ↔ 高并发库存扣减 是同一考点的业务化表述，字面无交集；另两个字面命中")
                        .build(),

                // ㊵ 假阳性强化：同一技术栈内共享英文前缀
                MemoryEvalSample.builder()
                        .id("mem_040")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("mysql主从复制", "binlog"))
                        .candidates(List.of(
                                c("MySQL主从复制", 52, 3, 3, true, 9, "hard"),
                                c("MySQL索引", 60, 2, 1, false, 26, "easy"),
                                c("MySQL事务", 62, 2, 1, false, 31, "easy")))
                        .relevantTopics(List.of("MySQL主从复制"))
                        .note("困难样本（hybrid 应更差）：三者共享 ASCII 词 mysql，词法会全部判为相关，"
                                + "而索引/事务与主从复制是不同考点；baseline 的严格 contains 只中正确的那一个。"
                                + "与 mem_020 共同构成『共享英文词假阳性』的证据")
                        .build(),

                // ═══════════════════════════════════════════════════════════
                // 组 H（mem_041 ~ mem_050）：语义通道专项 —— 缩写 / 上下位 / 业务化表述
                // 这一组的共同特征是「字面零交集或极弱交集」，两路降级下应大量失败，
                // 用于量化语义通道的独立贡献。
                // ═══════════════════════════════════════════════════════════

                // ㊶ 缩写密集：ThreadLocal / AQS / ReentrantLock 均指向 Java 并发
                MemoryEvalSample.builder()
                        .id("mem_041")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("aqs", "reentrantlock", "threadlocal"))
                        .candidates(List.of(
                                c("Java并发", 52, 4, 4, true, 7, "hard"),
                                c("线程池", 56, 3, 2, false, 15, "medium"),
                                c("MySQL索引", 64, 1, 1, false, 40, "easy")))
                        .relevantTopics(List.of("Java并发", "线程池"))
                        .note("语义专项：AQS 是 ReentrantLock 与线程池的底层同步框架，"
                                + "三个缩写与 topic 字面零交集")
                        .build(),

                // ㊷ 业务化表述 ↔ 技术考点
                MemoryEvalSample.builder()
                        .id("mem_042")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("订单超时关闭", "延迟任务"))
                        .candidates(List.of(
                                c("消息队列可靠性", 54, 3, 3, false, 12, "hard"),
                                c("Redis数据结构", 58, 2, 2, false, 18, "medium"),
                                c("Java集合", 66, 1, 1, false, 45, "easy")))
                        .relevantTopics(List.of("消息队列可靠性", "Redis数据结构"))
                        .note("语义专项：延迟任务的典型实现是 MQ 延迟消息或 Redis ZSet，"
                                + "属业务需求到技术方案的映射，字面零交集")
                        .build(),

                // ㊸ 上下位：数据库调优 ↔ 索引/锁
                MemoryEvalSample.builder()
                        .id("mem_043")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("慢查询优化", "执行计划"))
                        .candidates(List.of(
                                c("MySQL索引", 52, 4, 4, true, 8, "hard"),
                                c("MySQL锁", 58, 2, 2, false, 20, "medium"),
                                c("Redis持久化", 64, 1, 1, false, 38, "easy")))
                        .relevantTopics(List.of("MySQL索引"))
                        .note("语义专项：慢查询与执行计划的核心就是索引，但字面零交集；"
                                + "MySQL锁 不标（锁竞争属另一考点）")
                        .build(),

                // ㊹ 跨语言同概念：Go 的 GC 与 Java 的 GC 是不同实现
                MemoryEvalSample.builder()
                        .id("mem_044")
                        .position("Go后端开发工程师")
                        .jdSkills(List.of("三色标记", "写屏障"))
                        .candidates(List.of(
                                c("Go内存管理", 52, 3, 3, true, 9, "hard"),
                                c("JVM垃圾回收", 56, 3, 3, true, 16, "hard"),
                                c("Go channel", 62, 2, 1, false, 28, "medium")))
                        .relevantTopics(List.of("Go内存管理"))
                        .note("困难样本：三色标记/写屏障在 Go 与 JVM 都存在，语义上会同时命中两者，"
                                + "但岗位是 Go，JVM垃圾回收 不应召回——检验语义通道的跨语言误判风险")
                        .build(),

                // ㊺ 前端语义专项
                MemoryEvalSample.builder()
                        .id("mem_045")
                        .position("前端开发工程师")
                        .jdSkills(List.of("重排重绘", "事件循环"))
                        .candidates(List.of(
                                c("浏览器渲染原理", 52, 3, 3, true, 8, "hard"),
                                c("JavaScript异步机制", 56, 2, 2, false, 14, "medium"),
                                c("MySQL索引", 66, 1, 1, false, 50, "easy")))
                        .relevantTopics(List.of("浏览器渲染原理", "JavaScript异步机制"))
                        .note("语义专项：重排重绘属渲染原理、事件循环属 JS 异步机制，均字面零交集")
                        .build(),

                // ㊻ 大数据语义专项
                MemoryEvalSample.builder()
                        .id("mem_046")
                        .position("大数据开发工程师")
                        .jdSkills(List.of("exactly once", "checkpoint"))
                        .candidates(List.of(
                                c("Flink窗口", 58, 2, 2, false, 20, "medium"),
                                c("Flink状态一致性", 52, 3, 3, true, 9, "hard"),
                                c("MySQL索引", 66, 1, 1, false, 48, "easy")))
                        .relevantTopics(List.of("Flink状态一致性"))
                        .note("语义专项：exactly once 与 checkpoint 正是 Flink 状态一致性的核心机制；"
                                + "Flink窗口 不标（窗口计算属另一考点，但会共享 ASCII 词 flink 造成词法假阳性）")
                        .build(),

                // ㊼ 算法语义专项
                MemoryEvalSample.builder()
                        .id("mem_047")
                        .position("算法工程师")
                        .jdSkills(List.of("过拟合", "正则化"))
                        .candidates(List.of(
                                c("模型泛化能力", 52, 3, 3, true, 10, "hard"),
                                c("Transformer结构", 60, 2, 1, false, 22, "medium"),
                                c("梯度消失", 58, 2, 2, false, 26, "medium")))
                        .relevantTopics(List.of("模型泛化能力"))
                        .note("语义专项：过拟合/正则化正是泛化能力的核心议题，字面零交集")
                        .build(),

                // ㊽ 运维语义专项（补足该岗位样本量）
                MemoryEvalSample.builder()
                        .id("mem_048")
                        .position("运维开发工程师")
                        .jdSkills(List.of("滚动发布", "健康检查", "hpa"))
                        .candidates(List.of(
                                c("Kubernetes调度", 52, 3, 3, true, 8, "hard"),
                                c("容器隔离原理", 60, 2, 1, false, 24, "medium"),
                                c("MySQL索引", 66, 1, 1, false, 52, "easy")))
                        .relevantTopics(List.of("Kubernetes调度"))
                        .note("语义专项：滚动发布/HPA 均是 K8s 编排能力，字面零交集；"
                                + "补足运维岗位样本量（此前仅 2 条，指标波动大）")
                        .build(),

                // ㊾ C++ 语义专项
                MemoryEvalSample.builder()
                        .id("mem_049")
                        .position("C++后端开发工程师")
                        .jdSkills(List.of("raii", "内存泄漏排查"))
                        .candidates(List.of(
                                c("C++智能指针", 52, 3, 3, true, 9, "hard"),
                                c("C++内存管理", 56, 2, 2, false, 17, "hard"),
                                c("STL容器", 64, 1, 1, false, 33, "easy")))
                        .relevantTopics(List.of("C++智能指针", "C++内存管理"))
                        .note("语义专项：RAII 的典型载体就是智能指针，字面零交集")
                        .build(),

                // ㊿ 空黄金标准第三例：算法岗位面对纯后端薄弱点
                MemoryEvalSample.builder()
                        .id("mem_050")
                        .position("算法工程师")
                        .jdSkills(List.of("pytorch", "特征工程"))
                        .candidates(List.of(
                                c("MySQL主从复制", 52, 3, 3, true, 9, "hard"),
                                c("Kafka原理", 56, 2, 2, false, 18, "medium"),
                                c("TCP协议", 60, 2, 2, false, 30, "medium")))
                        .relevantTopics(List.of())
                        .note("正确行为是一条都不判强相关；语义通道也不应因『数据』『工程』等泛化词误召回")
                        .build(),

                // ═══════════════════════════════════════════════════════════
                // 组 I（mem_051 ~ mem_060）：语义通道的风险专项
                // 语义检索在短技术词上容易「相近但不同」，这一组专门检验它的误判边界。
                // ═══════════════════════════════════════════════════════════

                // 51 版本区分：语义几乎相同但考点不同
                MemoryEvalSample.builder()
                        .id("mem_051")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("http2", "多路复用"))
                        .candidates(List.of(
                                c("HTTP协议", 52, 3, 3, true, 9, "hard"),
                                c("IO模型", 56, 2, 2, false, 18, "hard"),
                                c("TCP协议", 60, 2, 2, false, 25, "medium")))
                        .relevantTopics(List.of("HTTP协议", "IO模型"))
                        .note("陷阱样本：『多路复用』在 HTTP2 与 IO 模型里是两个不同概念（流复用 vs epoll），"
                                + "但都该考；TCP协议 不标——它虽相邻但本场重点不在传输层")
                        .build(),

                // 52 语义相近但方向相反
                MemoryEvalSample.builder()
                        .id("mem_052")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("悲观锁", "乐观锁"))
                        .candidates(List.of(
                                c("MySQL锁", 52, 3, 3, true, 8, "hard"),
                                c("分布式锁", 56, 2, 2, false, 16, "hard"),
                                c("Java并发", 58, 3, 2, false, 21, "medium")))
                        .relevantTopics(List.of("MySQL锁", "Java并发", "分布式锁"))
                        .note("三者都含『锁』概念且都相关（数据库锁/CAS乐观锁/分布式互斥），"
                                + "语义通道应全部命中——这条是语义通道的正向样本")
                        .build(),

                // 53 泛化词陷阱：『优化』『设计』这类词语义极泛
                MemoryEvalSample.builder()
                        .id("mem_053")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("接口性能优化", "响应时间"))
                        .candidates(List.of(
                                c("系统设计", 56, 2, 2, false, 18, "hard"),
                                c("MySQL索引", 52, 4, 4, true, 8, "hard"),
                                c("前端性能优化", 60, 2, 2, false, 30, "medium")))
                        .relevantTopics(List.of("MySQL索引", "系统设计"))
                        .note("陷阱样本：『前端性能优化』与『接口性能优化』语义高度相似（都含性能优化），"
                                + "但岗位是后端，不应召回——检验语义通道会不会被泛化词带偏")
                        .build(),

                // 54 同一缩写在不同领域含义不同
                MemoryEvalSample.builder()
                        .id("mem_054")
                        .position("大数据开发工程师")
                        .jdSkills(List.of("olap", "列式存储"))
                        .candidates(List.of(
                                c("数据仓库分层", 52, 3, 3, true, 10, "hard"),
                                c("MySQL索引", 60, 2, 2, false, 28, "medium"),
                                c("Hive优化", 56, 2, 2, false, 15, "medium")))
                        .relevantTopics(List.of("数据仓库分层", "Hive优化"))
                        .note("语义专项：OLAP/列式存储 属数仓与 Hive 范畴；"
                                + "MySQL索引 是 OLTP 行存，不应召回——检验能否区分 OLAP/OLTP")
                        .build(),

                // 55 Android 语义专项（补足样本量）
                MemoryEvalSample.builder()
                        .id("mem_055")
                        .position("Android开发工程师")
                        .jdSkills(List.of("卡顿优化", "anr"))
                        .candidates(List.of(
                                c("Android性能优化", 52, 3, 3, true, 9, "hard"),
                                c("Android内存泄漏", 56, 2, 2, false, 17, "hard"),
                                c("MySQL索引", 66, 1, 1, false, 50, "easy")))
                        .relevantTopics(List.of("Android性能优化", "Android内存泄漏"))
                        .note("语义专项：ANR 与卡顿属性能问题，内存泄漏是其常见诱因；字面零交集")
                        .build(),

                // 56 Python 语义专项（补足样本量）
                MemoryEvalSample.builder()
                        .id("mem_056")
                        .position("Python后端开发工程师")
                        .jdSkills(List.of("orm", "n+1查询"))
                        .candidates(List.of(
                                c("Django ORM优化", 52, 3, 3, true, 8, "hard"),
                                c("MySQL索引", 56, 3, 2, false, 16, "medium"),
                                c("Python GIL", 62, 2, 1, false, 30, "medium")))
                        .relevantTopics(List.of("Django ORM优化", "MySQL索引"))
                        .note("N+1 查询是 ORM 典型问题、其优化最终落到索引与预加载；GIL 与本场无关")
                        .build(),

                // 57 测试开发语义专项（补足样本量）
                MemoryEvalSample.builder()
                        .id("mem_057")
                        .position("测试开发工程师")
                        .jdSkills(List.of("mock", "覆盖率", "ci"))
                        .candidates(List.of(
                                c("单元测试设计", 52, 3, 3, true, 9, "hard"),
                                c("接口测试设计", 58, 2, 2, false, 19, "medium"),
                                c("MySQL索引", 66, 1, 1, false, 46, "easy")))
                        .relevantTopics(List.of("单元测试设计", "接口测试设计"))
                        .note("语义专项：mock/覆盖率 是单测核心，CI 串联两类测试；字面零交集")
                        .build(),

                // 58 排序质量专项：三者均相关，但顽固 + 复发的应排第一
                MemoryEvalSample.builder()
                        .id("mem_058")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("redis", "缓存一致性"))
                        .candidates(List.of(
                                c("Redis数据结构", 62, 2, 1, false, 25, "easy"),
                                MemoryEvalSample.CandidateSpec.builder()
                                        .topic("Redis缓存问题").score(50).hitCount(7).wrongCount(6)
                                        .stubborn(true).relapseCount(2).daysAgo(5).difficulty("hard")
                                        .build(),
                                c("Redis持久化", 58, 2, 2, false, 15, "medium")))
                        .relevantTopics(List.of("Redis缓存问题"))
                        .note("三者字面均命中 redis，但只有『Redis缓存问题』对应『缓存一致性』且顽固复发，"
                                + "应排第一（MRR 应为 1.0）——检验记忆通道能否压制词法假阳性")
                        .build(),

                // 59 全部已掌握：正确行为是不重点考任何一个
                MemoryEvalSample.builder()
                        .id("mem_059")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("mysql", "redis", "java并发"))
                        .candidates(List.of(
                                c("MySQL索引", 86, 4, 1, false, 5, "hard"),
                                c("Redis持久化", 88, 3, 0, false, 8, "hard"),
                                c("Java并发", 85, 4, 1, false, 11, "hard")))
                        .relevantTopics(List.of())
                        .note("困难样本：三者近期 hard 题均 85+ 已掌握，正确行为是不作为重点薄弱点召回。"
                                + "这是 M1 筛选层（getWeakPoints 过滤已掌握项）的职责，"
                                + "M3 单独无法解决——属已知上限，两策略都会失败")
                        .build(),

                // 60 综合终测：字面 + 语义 + 已掌握 + 无关项四类混合
                MemoryEvalSample.builder()
                        .id("mem_060")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("kafka", "幂等消费", "分库分表"))
                        .candidates(List.of(
                                c("Kafka原理", 54, 3, 3, false, 12, "hard"),
                                c("消息队列可靠性", 50, 4, 4, true, 7, "hard"),
                                c("分库分表", 84, 3, 1, false, 9, "hard"),
                                c("React Hooks", 60, 2, 2, false, 40, "medium"),
                                c("Go并发", 62, 1, 1, false, 55, "easy")))
                        .relevantTopics(List.of("消息队列可靠性", "Kafka原理"))
                        .note("综合终测：Kafka 字面命中、幂等消费需语义、分库分表虽字面命中但已掌握(84分)不标、"
                                + "React/Go 完全无关。四类情形同时出现，检验整条链路")
                        .build(),

                // ═══════════════════════════════════════════════════════════
                // 组 J（mem_061 ~ mem_070）：大候选池专项 —— 评估 WEAK_POINT_CANDIDATE_POOL=30 是否最优
                //
                // 这一组是专门为 M4（候选池层次修正）构造的压力测试样本。
                // 核心特征：每样本 20~30 个候选，其中强相关项（relevant）刻意给高 mastery（score 65~75）、
                // 非顽固、低错误率 → priority 排在榜尾；同时塞入大量无关顽固点（score 40~55、stubborn=true、
                // wrongCount≥3）→ priority 排在头部。
                //
                // 预期：pool=10 时强相关项被无关顽固点挤掉 → Recall@3 显著低于 pool=30。
                // pool=30 时强相关项进入候选池 → 三路召回有机会把它排进 Top10。
                //
                // ⚠️ 红线：jd_skills 绝不能从同义词表抄词（M3 泄漏事故教训），需跑 LeakageReport 确认 ≤0.30。
                // ═══════════════════════════════════════════════════════════

                // 61 Java 岗位 + Go 历史薄弱点占满前排
                MemoryEvalSample.builder()
                        .id("mem_061")
                        .position("Java后端开发工程师")
                        .jdSkills(List.of("jvm调优", "spring事务", "mysql索引"))
                        .candidates(List.of(
                                // 12 个 Go 无关顽固点（priority 高）
                                c("Go并发", 42, 5, 5, true, 3, "hard"),
                                c("Go channel", 45, 4, 4, true, 5, "hard"),
                                c("Go内存管理", 40, 6, 5, true, 2, "hard"),
                                c("Go调度器", 48, 3, 3, true, 7, "hard"),
                                c("Go错误处理", 44, 4, 3, true, 4, "medium"),
                                c("Go反射", 50, 3, 3, true, 6, "medium"),
                                c("Go测试", 46, 4, 4, true, 8, "medium"),
                                c("Go Context", 42, 5, 4, true, 3, "hard"),
                                c("Go interface", 48, 3, 3, true, 5, "medium"),
                                c("Go泛型", 44, 4, 4, true, 6, "hard"),
                                c("Go依赖管理", 50, 3, 2, true, 9, "medium"),
                                c("Go协程泄漏", 42, 5, 5, true, 4, "hard"),
                                // 3 个 Java 强相关项（priority 低，score 65~75，非顽固，wrongCount=1）
                                c("JVM垃圾回收", 68, 3, 1, false, 10, "hard"),
                                c("Spring事务管理", 72, 2, 1, false, 12, "medium"),
                                c("MySQL索引优化", 70, 3, 1, false, 8, "hard"),
                                // 5 个其他领域填充
                                c("Kafka原理", 55, 2, 2, false, 20, "medium"),
                                c("Redis持久化", 58, 2, 1, false, 25, "medium"),
                                c("Docker容器", 52, 3, 2, false, 18, "medium"),
                                c("计算机网络", 56, 2, 1, false, 30, "easy"),
                                c("操作系统进程", 54, 3, 2, false, 22, "medium")))
                        .relevantTopics(List.of("JVM垃圾回收", "Spring事务管理", "MySQL索引优化"))
                        .note("组J：12个Go顽固点占满priority前排，3个Java强相关项score 68~72排在后50%。"
                                + "pool=10时Java项被截断；pool=30时进入候选池由RRF重排")
                        .build(),

                // 62 Python 岗位 + Java 历史薄弱点占满前排
                MemoryEvalSample.builder()
                        .id("mem_062")
                        .position("Python后端开发工程师")
                        .jdSkills(List.of("django", "celery", "redis缓存"))
                        .candidates(List.of(
                                // 10 个 Java 无关顽固点
                                c("Java并发", 42, 5, 5, true, 3, "hard"),
                                c("JVM垃圾回收", 45, 4, 4, true, 5, "hard"),
                                c("Spring原理", 40, 6, 5, true, 2, "hard"),
                                c("MyBatis", 48, 3, 3, true, 7, "hard"),
                                c("Java集合", 44, 4, 3, true, 4, "medium"),
                                c("Java IO", 50, 3, 3, true, 6, "medium"),
                                c("Java反射", 46, 4, 4, true, 8, "medium"),
                                c("SpringBoot", 42, 5, 4, true, 3, "hard"),
                                c("JPA", 48, 3, 3, true, 5, "medium"),
                                c("Java线程池", 44, 4, 4, true, 6, "hard"),
                                // 3 个 Python 强相关项
                                c("Django ORM", 70, 3, 1, false, 10, "medium"),
                                c("Celery异步任务", 68, 2, 1, false, 12, "hard"),
                                c("Redis缓存策略", 72, 3, 1, false, 8, "medium"),
                                // 7 个填充
                                c("MySQL索引", 55, 2, 2, false, 20, "medium"),
                                c("Kafka原理", 58, 2, 1, false, 25, "medium"),
                                c("Docker容器", 52, 3, 2, false, 18, "medium"),
                                c("Go并发", 56, 2, 1, false, 30, "easy"),
                                c("TCP协议", 54, 3, 2, false, 22, "medium"),
                                c("Linux运维", 50, 3, 3, false, 28, "medium"),
                                c("消息队列", 56, 2, 1, false, 35, "easy")))
                        .relevantTopics(List.of("Django ORM", "Celery异步任务", "Redis缓存策略"))
                        .note("组J：10个Java顽固点占前排，3个Python强相关项score 68~72排后50%")
                        .build(),

                // 63 Go 岗位 + Java 历史薄弱点（跨岗位场景，M4 的核心用例）
                MemoryEvalSample.builder()
                        .id("mem_063")
                        .position("Go后端开发工程师")
                        .jdSkills(List.of("goroutine", "go内存逃逸", "channel"))
                        .candidates(List.of(
                                // 15 个 Java 无关顽固点
                                c("Java并发", 40, 6, 6, true, 2, "hard"),
                                c("JVM垃圾回收", 42, 5, 5, true, 3, "hard"),
                                c("Spring事务", 44, 4, 4, true, 4, "hard"),
                                c("MyBatis", 46, 4, 3, true, 5, "hard"),
                                c("Java集合", 48, 3, 3, true, 6, "medium"),
                                c("Java IO", 42, 5, 4, true, 3, "hard"),
                                c("Java反射", 50, 3, 3, true, 7, "medium"),
                                c("SpringBoot", 44, 4, 4, true, 4, "hard"),
                                c("JPA", 46, 4, 3, true, 5, "medium"),
                                c("Java线程池", 42, 5, 5, true, 3, "hard"),
                                c("Java泛型", 48, 3, 3, true, 6, "medium"),
                                c("Java注解", 50, 3, 2, true, 8, "medium"),
                                c("Java流式API", 44, 4, 4, true, 5, "hard"),
                                c("JVM类加载", 42, 5, 5, true, 3, "hard"),
                                c("Java序列化", 48, 3, 3, true, 7, "medium"),
                                // 3 个 Go 强相关项（priority 低）
                                c("Go并发", 65, 3, 1, false, 10, "hard"),
                                c("Go内存管理", 70, 2, 1, false, 12, "hard"),
                                c("Go channel", 68, 3, 1, false, 8, "medium"),
                                // 5 个填充
                                c("Redis持久化", 55, 2, 2, false, 20, "medium"),
                                c("MySQL索引", 58, 2, 1, false, 25, "medium")))
                        .relevantTopics(List.of("Go并发", "Go内存管理", "Go channel"))
                        .note("组J核心用例：15个Java顽固点占满前排，3个Go强相关项排后50%。"
                                + "这正是M4要修的'跨岗位场景下强相关项被无关顽固点占满'场景")
                        .build(),

                // 64 大数据岗位 + 前端历史薄弱点
                MemoryEvalSample.builder()
                        .id("mem_064")
                        .position("大数据开发工程师")
                        .jdSkills(List.of("flink状态管理", "spark调优", "数据倾斜"))
                        .candidates(List.of(
                                // 10 个前端无关顽固点
                                c("React Hooks", 42, 5, 5, true, 3, "hard"),
                                c("虚拟DOM", 45, 4, 4, true, 5, "hard"),
                                c("CSS布局", 40, 6, 5, true, 2, "hard"),
                                c("Webpack", 48, 3, 3, true, 7, "medium"),
                                c("Vue原理", 44, 4, 3, true, 4, "medium"),
                                c("浏览器渲染", 50, 3, 3, true, 6, "medium"),
                                c("TypeScript", 46, 4, 4, true, 8, "medium"),
                                c("Redux", 42, 5, 4, true, 3, "hard"),
                                c("前端性能", 48, 3, 3, true, 5, "medium"),
                                c("HTML5", 50, 3, 2, true, 9, "easy"),
                                // 3 个大数据强相关项
                                c("Flink状态一致性", 70, 3, 1, false, 10, "hard"),
                                c("Spark内存管理", 68, 2, 1, false, 12, "medium"),
                                c("数据倾斜处理", 72, 3, 1, false, 8, "hard"),
                                // 5 个填充
                                c("Kafka原理", 55, 2, 2, false, 20, "medium"),
                                c("Hive优化", 58, 2, 1, false, 25, "medium"),
                                c("MySQL索引", 52, 3, 2, false, 18, "medium"),
                                c("Java并发", 56, 2, 1, false, 30, "easy"),
                                c("Docker容器", 54, 3, 2, false, 22, "medium")))
                        .relevantTopics(List.of("Flink状态一致性", "Spark内存管理", "数据倾斜处理"))
                        .note("组J：10个前端顽固点占前排，3个大数据强相关项排后50%")
                        .build(),

                // 65 C++ 岗位 + Java 历史薄弱点
                MemoryEvalSample.builder()
                        .id("mem_065")
                        .position("C++后端开发工程师")
                        .jdSkills(List.of("智能指针", "stl", "模板元编程"))
                        .candidates(List.of(
                                // 8 个 Java 无关顽固点
                                c("Java并发", 42, 5, 5, true, 3, "hard"),
                                c("JVM垃圾回收", 45, 4, 4, true, 5, "hard"),
                                c("Spring原理", 40, 6, 5, true, 2, "hard"),
                                c("MyBatis", 48, 3, 3, true, 7, "medium"),
                                c("Java集合", 44, 4, 3, true, 4, "medium"),
                                c("Java IO", 50, 3, 3, true, 6, "medium"),
                                c("Java反射", 46, 4, 4, true, 8, "medium"),
                                c("SpringBoot", 42, 5, 4, true, 3, "hard"),
                                // 3 个 C++ 强相关项
                                c("C++智能指针", 68, 3, 1, false, 10, "hard"),
                                c("STL容器", 70, 2, 1, false, 12, "medium"),
                                c("C++模板", 72, 3, 1, false, 8, "hard"),
                                // 4 个填充
                                c("Go并发", 55, 2, 2, false, 20, "medium"),
                                c("MySQL索引", 58, 2, 1, false, 25, "medium"),
                                c("Redis持久化", 52, 3, 2, false, 18, "medium"),
                                c("TCP协议", 56, 2, 1, false, 30, "easy")))
                        .relevantTopics(List.of("C++智能指针", "STL容器", "C++模板"))
                        .note("组J：8个Java顽固点占前排，3个C++强相关项排后50%")
                        .build()
        );
    }
}
