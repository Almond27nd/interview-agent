/**
 */
package com.interview.agent.graph;

import com.interview.agent.model.AnswerScore;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 面试过程回调（与 Go 版本一致）
 */
public interface InterviewCallbacks {

    /** 阶段变化回调 */
    void onStageChange(String stage, String msg);

    /** 题目回调（完整文本，出题/追问流式生成结束后调用，内容可能比流式片段拼接结果多出来源标注等后缀） */
    void onQuestion(int questionNum, String content);

    /**
     * 题目流式增量回调：出题/追问由 LLM 逐 token 生成过程中，每收到一个增量片段就回调一次，
     * 用于前端实现打字机效果。与 {@link #onQuestion} 配合使用——先收到 N 次增量，
     * 最后再收到一次完整文本收尾（可能包含增量阶段没有的来源标注后缀）。
     */
    void onQuestionDelta(int questionNum, String delta);

    /** 评分回调 */
    void onScore(AnswerScore score);

    /** 报告回调 */
    void onReport(String report);

    /** 复习计划回调 */
    void onReviewPlan(String plan);

    /** 限时答题：候选人未在限定时间内作答的通知回调（用于前端提示"已超时"） */
    void onTimeout(int questionNum);

    /**
     * 获取用户回答（阻塞等待，带超时）。超时未收到回答时抛出 {@link AnswerTimeoutException}，
     * 由 Orchestrator 按"未回答"处理（记 0 分，继续下一题），而非中断整场面试。
     */
    String getUserAnswer() throws InterruptedException, UserQuitException, AnswerTimeoutException;

    /**
     * 请求用户对某个 stage 的输入做补充说明（阻塞等待），用于 LLM 语义自评"信息不足"时的
     * 人机交互。与 {@link #getUserAnswer()} 复用同一套阻塞机制，但语义独立：这不是"面试问答"，
     * 前端应据此渲染"请补充信息"而非"请回答面试题"的提示。
     *
     * @param stage    当前所在的业务阶段（如 "jd_analysis"），供前端区分展示
     * @param question 需要向用户展示的追问文案（来自 LLM 自评结果）
     * @return 用户补充输入的文本
     */
    String requestClarification(String stage, String question) throws InterruptedException, UserQuitException;
}
