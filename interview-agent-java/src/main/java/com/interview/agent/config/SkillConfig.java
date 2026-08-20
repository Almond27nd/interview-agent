/**
 */
package com.interview.agent.config;

import com.interview.agent.rag.BM25Manager;
import com.interview.agent.rag.MilvusStore;
import com.interview.agent.skill.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Skill 技能系统配置：注册所有 Skill（先注册优先级高）
 */
@Configuration
public class SkillConfig {

    @Bean
    public SkillRegistry skillRegistry(ChatModel chatModel, MilvusStore milvusStore, BM25Manager bm25Manager) {
        SkillRegistry registry = new SkillRegistry();
        registry.register(new QuickQuizSkill(chatModel, milvusStore, bm25Manager));
        registry.register(new ConceptTutorSkill(chatModel, milvusStore, bm25Manager));
        registry.register(new ProjectHighlightSkill(chatModel));
        registry.register(new TechCompareSkill(chatModel, milvusStore, bm25Manager));
        return registry;
    }
}
