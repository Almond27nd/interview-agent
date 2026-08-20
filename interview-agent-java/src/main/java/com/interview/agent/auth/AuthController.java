/**
 */
package com.interview.agent.auth;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 注册登录 Controller（与 Go 版本 auth/handler.go 路径和响应格式一致）
 * POST /api/register
 * POST /api/login
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final JwtService jwtService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody AuthRequest request) {
        try {
            if (request.getUsername() == null || request.getUsername().isEmpty()
                    || request.getPassword() == null || request.getPassword().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "用户名和密码不能为空"));
            }
            String token = jwtService.register(request.getUsername(), request.getPassword());
            return ResponseEntity.ok(Map.of("token", token, "username", request.getUsername()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest request) {
        try {
            if (request.getUsername() == null || request.getUsername().isEmpty()
                    || request.getPassword() == null || request.getPassword().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "用户名和密码不能为空"));
            }
            String token = jwtService.login(request.getUsername(), request.getPassword());
            return ResponseEntity.ok(Map.of("token", token, "username", request.getUsername()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @Data
    public static class AuthRequest {
        private String username;
        private String password;
    }
}
