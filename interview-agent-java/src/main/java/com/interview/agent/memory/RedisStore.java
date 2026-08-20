/**
 */
package com.interview.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 存储层（与 Go 版本 key 模式一致）
 * - Profile: interview:profile:{userID}
 * - Session: interview:session:{sessionID}
 * - File hash: interview:file_hash:{userID}:{filename}
 */
@Slf4j
@Component
public class RedisStore {

    private static final String PROFILE_PREFIX = "interview:profile:";
    private static final String SESSION_PREFIX = "interview:session:";
    private static final String FILE_HASH_PREFIX = "interview:file_hash:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public void saveProfile(UserProfile profile) {
        try {
            String json = objectMapper.writeValueAsString(profile);
            redisTemplate.opsForValue().set(PROFILE_PREFIX + profile.getUserId(), json);
        } catch (Exception e) {
            log.warn("[RedisStore] 保存 Profile 失败: {}", e.getMessage());
        }
    }

    public UserProfile loadProfile(String userId) {
        try {
            String json = redisTemplate.opsForValue().get(PROFILE_PREFIX + userId);
            if (json == null) return null;
            return objectMapper.readValue(json, UserProfile.class);
        } catch (Exception e) {
            log.warn("[RedisStore] 加载 Profile 失败: {}", e.getMessage());
            return null;
        }
    }

    public void saveSession(String sessionId, String data, Duration ttl) {
        redisTemplate.opsForValue().set(SESSION_PREFIX + sessionId, data, ttl);
    }

    public String loadSession(String sessionId) {
        return redisTemplate.opsForValue().get(SESSION_PREFIX + sessionId);
    }

    /**
     * 获取文件 hash（用于题库上传去重）
     */
    public String getFileHash(String userId, String filename) {
        return redisTemplate.opsForValue().get(FILE_HASH_PREFIX + userId + ":" + filename);
    }

    /**
     * 保存文件 hash
     */
    public void saveFileHash(String userId, String filename, String hash) {
        redisTemplate.opsForValue().set(FILE_HASH_PREFIX + userId + ":" + filename, hash);
    }
}
