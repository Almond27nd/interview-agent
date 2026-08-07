/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.interview.agent.agent.Evaluator;
import com.interview.agent.agent.ReviewPlanner;
import com.interview.agent.auth.JwtService;
import com.interview.agent.memory.MySQLStore;
import com.interview.agent.memory.UserProfile;
import com.interview.agent.model.EvaluationReport;
import com.interview.agent.model.ReviewPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史面试查看接口。
 * 由于当前 SecurityConfig 对 /api/** 是 permitAll（没有全局 JWT 过滤器），
 * 这里手动解析 Authorization: Bearer <token> 头并校验，与 WebSocket 层的认证方式保持一致。
 */
@Slf4j
@RestController
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
public class InterviewHistoryController {

    private final JwtService jwtService;
    private final MySQLStore mysqlStore;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * 历史面试列表（最近 20 场，按时间倒序）。
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String userId = resolveUserId(authHeader);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录或 token 无效"));
        }

        List<UserProfile.InterviewRecord> records = mysqlStore.getRecentInterviewRecords(userId, 20);
        return ResponseEntity.ok(Map.of("list", records));
    }

    /**
     * 单场面试详情：解析 report_json/review_plan_json 为结构化对象后返回。
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<?> detail(@PathVariable String sessionId,
                                     @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String userId = resolveUserId(authHeader);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录或 token 无效"));
        }

        Map<String, Object> row = mysqlStore.getInterviewDetail(userId, sessionId);
        if (row == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "面试记录不存在"));
        }

        EvaluationReport report = null;
        ReviewPlan reviewPlan = null;
        try {
            String reportJson = (String) row.get("reportJson");
            if (reportJson != null && !reportJson.isBlank()) {
                report = objectMapper.readValue(reportJson, EvaluationReport.class);
            }
        } catch (Exception e) {
            log.warn("[InterviewHistory] 解析 report_json 失败 sessionId={}: {}", sessionId, e.getMessage());
        }
        try {
            String reviewPlanJson = (String) row.get("reviewPlanJson");
            if (reviewPlanJson != null && !reviewPlanJson.isBlank()) {
                reviewPlan = objectMapper.readValue(reviewPlanJson, ReviewPlan.class);
            }
        } catch (Exception e) {
            log.warn("[InterviewHistory] 解析 review_plan_json 失败 sessionId={}: {}", sessionId, e.getMessage());
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("sessionId", row.get("sessionId"));
        resp.put("position", row.get("position"));
        resp.put("overallScore", row.get("overallScore"));
        resp.put("createdAt", row.get("createdAt"));
        resp.put("report", report);
        resp.put("reviewPlan", reviewPlan);
        // 同时给出与实时 WS 推送一致的 Markdown 渲染结果，前端可直接复用 ReportCard/ReviewPlanCard 展示，
        // 避免为历史详情页重新实现一套 report/reviewPlan 的排版逻辑。
        resp.put("reportMarkdown", report != null ? Evaluator.formatReport(report) : null);
        resp.put("reviewPlanMarkdown", reviewPlan != null ? ReviewPlanner.formatReviewPlan(reviewPlan) : null);
        return ResponseEntity.ok(resp);
    }

    private String resolveUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            return null;
        }
        try {
            return jwtService.validateToken(token);
        } catch (Exception e) {
            log.warn("[InterviewHistory] token 校验失败: {}", e.getMessage());
            return null;
        }
    }
}
