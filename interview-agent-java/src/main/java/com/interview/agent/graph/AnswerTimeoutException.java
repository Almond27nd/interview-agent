/**
 */
package com.interview.agent.graph;

/**
 * 候选人在限定时间内未作答（限时答题功能）。
 * 与 {@link UserQuitException} 语义不同：用户没有主动退出面试，只是这一题超时，
 * 流程应把该题按"未回答"记为 0 分并继续下一题，而不是终止整场面试。
 */
public class AnswerTimeoutException extends Exception {
    public AnswerTimeoutException() {
        super("候选人未在限定时间内作答");
    }
}
