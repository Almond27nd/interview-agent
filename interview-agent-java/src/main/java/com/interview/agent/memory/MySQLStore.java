/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MySQL 存储层（与 Go 版本表结构一致）
 * 使用 JPA EntityManager 执行原生 SQL，确保表结构与 Go 版本完全对齐。
 */
@Slf4j
@Component
public class MySQLStore {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * 初始化建表（与 Go 版本一致，自动建表）。
     * Spring Data JPA 的 ddl-auto 只处理 @Entity 的 users 表；user_profiles / interview_records
     * 是用原生 SQL 操作的非实体表，必须在此显式建表。
     * 用 JdbcTemplate 执行 DDL（自带连接、自动提交），避免 @PostConstruct 上 @Transactional
     * 自调用代理不生效导致的 TransactionRequired 问题。
     */
    @PostConstruct
    public void migrate() {
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_profiles (
                    user_id VARCHAR(128) PRIMARY KEY,
                    name VARCHAR(256) DEFAULT '',
                    skill_level JSON,
                    weak_points JSON,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS interview_records (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id VARCHAR(128) NOT NULL,
                    session_id VARCHAR(128) NOT NULL UNIQUE,
                    position VARCHAR(256) DEFAULT '',
                    overall_score DOUBLE DEFAULT 0,
                    report_json MEDIUMTEXT,
                    review_plan_json MEDIUMTEXT,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_user_id (user_id),
                    INDEX idx_session_id (session_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            log.info("[MySQLStore] 表结构就绪");
        } catch (Exception e) {
            log.warn("[MySQLStore] 建表异常（可能已存在）: {}", e.getMessage());
        }
    }

    @Transactional
    public void saveProfile(UserProfile profile) {
        try {
            String skillLevelJson = objectMapper.writeValueAsString(profile.getSkillLevel());
            String weakPointsJson = objectMapper.writeValueAsString(profile.getWeakPoints());

            entityManager.createNativeQuery("""
                INSERT INTO user_profiles (user_id, name, skill_level, weak_points)
                VALUES (:userId, :name, :skillLevel, :weakPoints)
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name),
                    skill_level = VALUES(skill_level),
                    weak_points = VALUES(weak_points),
                    updated_at = NOW()
            """)
            .setParameter("userId", profile.getUserId())
            .setParameter("name", profile.getName() != null ? profile.getName() : "")
            .setParameter("skillLevel", skillLevelJson)
            .setParameter("weakPoints", weakPointsJson)
            .executeUpdate();
        } catch (Exception e) {
            log.error("[MySQLStore] 保存 Profile 失败: {}", e.getMessage());
        }
    }

    public UserProfile loadProfile(String userId) {
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> results = entityManager.createNativeQuery(
                "SELECT user_id, name, skill_level, weak_points FROM user_profiles WHERE user_id = :userId"
            ).setParameter("userId", userId).getResultList();

            if (results.isEmpty()) return null;

            Object[] row = results.get(0);
            UserProfile profile = new UserProfile();
            profile.setUserId((String) row[0]);
            profile.setName((String) row[1]);

            if (row[2] != null) {
                profile.setSkillLevel(objectMapper.readValue(row[2].toString(),
                        new TypeReference<Map<String, String>>() {}));
            }
            if (row[3] != null) {
                profile.setWeakPoints(objectMapper.readValue(row[3].toString(),
                        new TypeReference<List<UserProfile.WeakPoint>>() {}));
            }

            return profile;
        } catch (Exception e) {
            log.error("[MySQLStore] 加载 Profile 失败: {}", e.getMessage());
            return null;
        }
    }

    @Transactional
    public void saveInterviewRecord(String userId, UserProfile.InterviewRecord record,
                                     String reportJson, String reviewPlanJson) {
        try {
            entityManager.createNativeQuery("""
                INSERT INTO interview_records (user_id, session_id, position, overall_score, report_json, review_plan_json)
                VALUES (:userId, :sessionId, :position, :score, :report, :reviewPlan)
            """)
            .setParameter("userId", userId)
            .setParameter("sessionId", record.getSessionId())
            .setParameter("position", record.getPosition())
            .setParameter("score", record.getOverallScore())
            .setParameter("report", reportJson)
            .setParameter("reviewPlan", reviewPlanJson)
            .executeUpdate();
        } catch (Exception e) {
            log.error("[MySQLStore] 保存面试记录失败: {}", e.getMessage());
        }
    }

    /**
     * 查询某用户针对某岗位最近的 N 场面试摘要（session_id/position/overall_score/created_at），
     * 按时间倒序。用于出题规划阶段感知"候选人是否多次面试过同一岗位、历史分数趋势"。
     */
    public List<UserProfile.InterviewRecord> getRecentInterviewRecords(String userId, String position, int limit) {
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> results = entityManager.createNativeQuery("""
                SELECT session_id, position, overall_score, created_at
                FROM interview_records
                WHERE user_id = :userId AND position = :position
                ORDER BY created_at DESC
                LIMIT :limit
            """)
            .setParameter("userId", userId)
            .setParameter("position", position)
            .setParameter("limit", limit)
            .getResultList();

            List<UserProfile.InterviewRecord> records = new ArrayList<>();
            for (Object[] row : results) {
                records.add(UserProfile.InterviewRecord.builder()
                        .sessionId((String) row[0])
                        .position((String) row[1])
                        .overallScore(row[2] != null ? ((Number) row[2]).doubleValue() : 0)
                        .date(toLocalDateTime(row[3]))
                        .build());
            }
            return records;
        } catch (Exception e) {
            log.warn("[MySQLStore] 查询历史面试摘要失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 查询某用户针对某岗位最近一次生成的复习计划 JSON（review_plan_json）。
     * 用于生成新一份复习计划时参考"上次计划过什么"，避免重复推荐、突出薄弱点是否已改善。
     * 无历史记录时返回 null。
     */
    public String getLatestReviewPlanJson(String userId, String position) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> results = entityManager.createNativeQuery("""
                SELECT review_plan_json
                FROM interview_records
                WHERE user_id = :userId AND position = :position AND review_plan_json IS NOT NULL
                ORDER BY created_at DESC
                LIMIT 1
            """)
            .setParameter("userId", userId)
            .setParameter("position", position)
            .getResultList();

            if (results.isEmpty() || results.get(0) == null) {
                return null;
            }
            return results.get(0).toString();
        } catch (Exception e) {
            log.warn("[MySQLStore] 查询上一次复习计划失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 查询某用户最近 N 场面试摘要（不限岗位），按时间倒序。
     * 用于聊天入口"查看历史"意图 —— 用户可能问历史时并不特指某个岗位。
     */
    public List<UserProfile.InterviewRecord> getRecentInterviewRecords(String userId, int limit) {
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> results = entityManager.createNativeQuery("""
                SELECT session_id, position, overall_score, created_at
                FROM interview_records
                WHERE user_id = :userId
                ORDER BY created_at DESC
                LIMIT :limit
            """)
            .setParameter("userId", userId)
            .setParameter("limit", limit)
            .getResultList();

            List<UserProfile.InterviewRecord> records = new ArrayList<>();
            for (Object[] row : results) {
                records.add(UserProfile.InterviewRecord.builder()
                        .sessionId((String) row[0])
                        .position((String) row[1])
                        .overallScore(row[2] != null ? ((Number) row[2]).doubleValue() : 0)
                        .date(toLocalDateTime(row[3]))
                        .build());
            }
            return records;
        } catch (Exception e) {
            log.warn("[MySQLStore] 查询用户历史面试摘要失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 查询某用户某场面试的完整详情（report_json/review_plan_json 原始字符串 + 摘要字段）。
     * 同时用 user_id 过滤，确保只能查自己名下的面试记录（防止越权查询他人 session）。
     * 未找到（不存在或不属于该用户）时返回 null。
     */
    public Map<String, Object> getInterviewDetail(String userId, String sessionId) {
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> results = entityManager.createNativeQuery("""
                SELECT session_id, position, overall_score, report_json, review_plan_json, created_at
                FROM interview_records
                WHERE user_id = :userId AND session_id = :sessionId
            """)
            .setParameter("userId", userId)
            .setParameter("sessionId", sessionId)
            .getResultList();

            if (results.isEmpty()) return null;

            Object[] row = results.get(0);
            Map<String, Object> detail = new java.util.HashMap<>();
            detail.put("sessionId", row[0]);
            detail.put("position", row[1]);
            detail.put("overallScore", row[2] != null ? ((Number) row[2]).doubleValue() : 0);
            detail.put("reportJson", row[3] != null ? row[3].toString() : null);
            detail.put("reviewPlanJson", row[4] != null ? row[4].toString() : null);
            detail.put("createdAt", toLocalDateTime(row[5]));
            return detail;
        } catch (Exception e) {
            log.error("[MySQLStore] 查询面试详情失败: {}", e.getMessage());
            return null;
        }
    }

    private LocalDateTime toLocalDateTime(Object dateObj) {
        if (dateObj instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (dateObj instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }
}
