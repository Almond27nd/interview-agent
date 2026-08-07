/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.auth;

import com.interview.agent.config.AppConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 认证服务（与 Go 版本 auth/auth.go 逻辑一致）
 */
@Slf4j
@Service
public class JwtService {

    private final SecretKey secretKey;
    private final long expiration;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;

    public JwtService(AppConfig appConfig, PasswordEncoder passwordEncoder, UserRepository userRepository) {
        // 确保密钥至少 32 字节（HMAC-SHA256 要求）
        String secret = appConfig.getJwt().getSecret();
        if (secret.length() < 32) {
            secret = secret + "0".repeat(32 - secret.length());
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = appConfig.getJwt().getExpiration();
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
    }

    /**
     * 注册新用户
     */
    public String register(String username, String password) {
        if (userRepository.existsByUsername(username)) {
            throw new RuntimeException("用户名已存在");
        }

        UserEntity user = UserEntity.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .build();
        userRepository.save(user);

        log.info("[Auth] 用户注册成功: {}", username);
        return generateToken(username);
    }

    /**
     * 登录
     */
    public String login(String username, String password) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("密码错误");
        }

        log.info("[Auth] 用户登录成功: {}", username);
        return generateToken(username);
    }

    /**
     * 生成 JWT Token
     */
    public String generateToken(String username) {
        return Jwts.builder()
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 验证 Token 并返回用户名
     */
    public String validateToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (Exception e) {
            throw new RuntimeException("Token 无效: " + e.getMessage());
        }
    }
}
