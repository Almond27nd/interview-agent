/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.graph;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 面试流程里所有"暂停执行、等待用户输入"的场景（面试问答 {@code getUserAnswer}、
 * JD 语义自评不足时的澄清 {@code requestClarification}）统一走这里落地阻塞逻辑。
 * <p>
 * 收敛的是原来在 {@code WebSocketHandler} 里两处各写一遍、后续每新增一个暂停点
 * 还要再抄一遍的重复代码：
 * 1. 从 {@code answerCh} 阻塞取值（可选超时）；
 * 2. 识别"用户退出指令"并统一转换成 {@link UserQuitException}。
 * <p>
 * 命名与定位（对应 HITL 语境下"人在环暂停点"这一概念，避免和框架自带的
 * {@code HumanInTheLoopHook} 混淆）：那是面向"LLM 自主决定调用工具，人来
 * approve/edit/reject"设计的、依赖 checkpoint 持久化、可跨进程恢复的机制；
 * 这里是面向"业务代码判断信息不足，需要人补一段自由文本"的轻量实现——
 * 同步阻塞当前线程，不落 checkpoint，进程/连接需要保持存活，靠上层
 * （WebSocketHandler 的断线重连宽限期）兜底。二者解决的不是同一个问题，
 * 不能相互替代。
 */
public final class HumanGate {

    /** 用户主动退出面试的口令（与原逻辑保持一致，未做任何行为变更） */
    private static final List<String> QUIT_PHRASES = List.of("/quit", "/exit", "退出", "结束面试");

    /**
     * "以当前信息继续"信号量：requestClarification 场景下，前端渲染显式按钮供用户选择
     * （继续 / 补充 / 退出），点击"继续"时不走自由文本输入框，而是由
     * {@code WebSocketHandler} 直接把这个约定好的哨兵值塞进 answerCh。
     * <p>
     * 之所以不复用真实文本比较（如识别"没有了"之类的自然语言），是因为那需要额外一次 LLM
     * 语义判断且存在误判风险；用户点击的是一个明确的按钮，天然对应一个明确的哨兵值，
     * 不需要猜测意图。取一个不可能与用户真实输入的自由文本撞车的值。
     */
    public static final String CONTINUE_WITH_CURRENT_INFO = "__CLARIFY_CONTINUE_WITH_CURRENT_INFO__";

    private HumanGate() {
    }

    /** 无超时阻塞等待一次用户输入（用于 requestClarification 等不限时的暂停点） */
    public static String await(BlockingQueue<String> answerCh) throws InterruptedException, UserQuitException {
        return checkQuit(answerCh.take());
    }

    /** 带超时阻塞等待一次用户输入（用于 getUserAnswer 限时答题场景） */
    public static String await(BlockingQueue<String> answerCh, long timeout, TimeUnit unit)
            throws InterruptedException, UserQuitException, AnswerTimeoutException {
        String answer = answerCh.poll(timeout, unit);
        if (answer == null) {
            throw new AnswerTimeoutException();
        }
        return checkQuit(answer);
    }

    private static String checkQuit(String answer) throws UserQuitException {
        if (QUIT_PHRASES.contains(answer)) {
            throw new UserQuitException();
        }
        return answer;
    }
}
