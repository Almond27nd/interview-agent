package com.interview.agent.graph;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * ArchUnit 架构边界测试 —— harness engineering 里"约束必须机械化执行"原则的落地。
 *
 * <p>包间依赖方向是项目的架构前提。本类把"依赖只能向下不能向上"锁进 CI——违反即构建失败。
 *
 * <p>分层定义（自下而上）：
 * <pre>
 * L5  handler, auth          传输层 / 认证
 * L4  graph                  流程编排
 * L3  agent, skill           Agent 实现 / Skill 技能系统
 * L2  memory, loader, mcp    记忆 / 文档加载 / 工具
 * L1  rag, config            检索 / 配置
 * L0  model                  纯领域模型（零内部依赖）
 * </pre>
 *
 * <p>已知豁免：config 包可以引用上层包做 Spring @Configuration 装配（见 ADR-005）。
 * <p>注意：ArchUnit 的 `..` 通配会匹配子包，导致跨包引用被大量误报。
 * 本类用 `resideInAPackage` 精确匹配顶层包，避免误报。
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.interview.agent");

    /**
     * model 包不得依赖任何上层包。
     * model 是纯领域模型（DTO/record），应该是零内部依赖的叶子。
     */
    @Test
    void model_isLeaf_noUpwardDependencies() {
        noClasses().that()
                .resideInAPackage("com.interview.agent.model")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.interview.agent.agent",
                        "com.interview.agent.graph",
                        "com.interview.agent.memory",
                        "com.interview.agent.rag",
                        "com.interview.agent.handler",
                        "com.interview.agent.mcp",
                        "com.interview.agent.skill",
                        "com.interview.agent.config",
                        "com.interview.agent.loader")
                .check(CLASSES);
    }

    /**
     * rag 包不得依赖编排层、Agent 层或记忆层。
     * rag 是底层检索能力（Milvus/BM25/Rerank），应该只被上层调用，不能反过来。
     */
    @Test
    void rag_doesNotDependOnUpperLayers() {
        noClasses().that()
                .resideInAPackage("com.interview.agent.rag")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.interview.agent.agent",
                        "com.interview.agent.graph",
                        "com.interview.agent.handler",
                        "com.interview.agent.memory",
                        "com.interview.agent.mcp",
                        "com.interview.agent.skill",
                        "com.interview.agent.loader")
                .check(CLASSES);
    }

    /**
     * memory 包不得依赖 Agent 层或编排层。
     * 记忆是被消费的能力，不能反过来调用 Agent 或感知流程。
     */
    @Test
    void memory_doesNotDependOnAgentOrGraph() {
        noClasses().that()
                .resideInAPackage("com.interview.agent.memory")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.interview.agent.agent",
                        "com.interview.agent.graph",
                        "com.interview.agent.handler",
                        "com.interview.agent.skill")
                .check(CLASSES);
    }

    /**
     * agent 包不得依赖 graph 编排层。
     * Agent 不应感知流程编排，保持可独立测试。
     */
    @Test
    void agent_doesNotDependOnGraph() {
        noClasses().that()
                .resideInAPackage("com.interview.agent.agent")
                .should().dependOnClassesThat()
                .resideInAPackage("com.interview.agent.graph")
                .check(CLASSES);
    }

    /**
     * 无循环依赖（按顶层包检查）。
     * config 包因 Spring @Configuration 装配会产生与 skill/handler 的双向引用（见 ADR-005），
     * 将 config 从循环检查中排除。
     */
    @Test
    void noCyclicDependencies() {
        DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass> isConfig =
                new DescribedPredicate<>("reside in config") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaClass input) {
                        return input.getPackageName().equals("com.interview.agent.config");
                    }
                };
        slices()
                .matching("com.interview.agent.(*)")
                .should().beFreeOfCycles()
                .ignoreDependency(isConfig, DescribedPredicate.alwaysTrue())
                .ignoreDependency(DescribedPredicate.alwaysTrue(), isConfig)
                .check(CLASSES);
    }
}
