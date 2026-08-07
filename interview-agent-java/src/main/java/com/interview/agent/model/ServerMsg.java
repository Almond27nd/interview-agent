/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * WebSocket 服务端消息，JSON 字段与 Go 版本完全一致。
 * 使用 NON_NULL 策略：空字段不序列化，与 Go 的 omitempty 对齐。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServerMsg {

    private String type;        // chat_reply/stage_change/question/score/report/review_plan/error/upload_result/interview_complete
    private String content;
    private String stage;
    private String message;

    @JsonProperty("question_num")
    private Integer questionNum;

    private Double score;
    private String feedback;

    @JsonProperty("key_points_hit")
    private List<String> keyPointsHit;

    @JsonProperty("key_points_missed")
    private List<String> keyPointsMissed;

    /**
     * 断线重连（type=resumed）时，告知前端"距离后端真正超时裁决还剩多少秒"，
     * 而不是让前端简单地把倒计时重置为完整时长——后端 answerCh 的 poll 超时是从
     * 原始提问时刻起算的，重连不会重置它，前端倒计时展示需要与之保持一致。
     */
    @JsonProperty("remaining_seconds")
    private Integer remainingSeconds;
}
