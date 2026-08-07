/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.interview.agent.agent.*;
import com.interview.agent.auth.JwtService;
import com.interview.agent.graph.*;
import com.interview.agent.loader.*;
import com.interview.agent.memory.*;
import com.interview.agent.model.*;
import com.interview.agent.rag.*;
import com.interview.agent.skill.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * WebSocket 处理器（与 Go 版本 ws_handler.go 完全一致的协议和逻辑）
 * - handleChat：3 级优先级（active skill → skill match → ChatAgent）
 * - handleStartInterview：创建 Orchestrator，异步运行面试
 * - handleAnswer：通过 answerCh 传递用户回答
 * - handleUploadQuestions：base64 解码 → SHA256 去重 → LLM 解析 → Milvus + BM25
 * - handleQuitInterview：用户主动终止
 */
@Slf4j
@Component
public class WebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private final Orchestrator orchestrator;
    private final ChatAgent chatAgent;
    private final IntentRouter intentRouter;
    private final SkillRegistry skillRegistry;
    private final DocumentLoader documentLoader;
    private final QuestionParser questionParser;
    private final WebLoader webLoader;
    private final MilvusStore milvusStore;
    private final BM25Manager bm25Manager;
    private final RedisStore redisStore;
    private final JwtService jwtService;
    private final ChatModel chatModel;
    private final MySQLStore mysqlStore;

    /**
     * 限时答题：每题候选人思考+作答的最长等待时间（秒），超时按"未回答"处理。
     * 只限制"等待用户输入"这一步的阻塞时长，不影响 LLM 出题/评分调用本身。
     */
    @Value("${app.interview.answer-timeout-seconds:120}")
    private long answerTimeoutSeconds = 120;

    /** session 管理：按 WebSocketSession.getId()（连接级）索引，用于 handleTextMessage 等消息路由 */
    private final Map<String, WSSession> sessions = new ConcurrentHashMap<>();

    /**
     * 断线重连支持：按 userID 索引，用于重连时找回同一用户"正在进行中"的面试状态（同后端实例内）。
     * 面试运行在 asyncExecutor 的独立线程中，其内部闭包（InterviewCallbacks 匿名类）只捕获了
     * WSSession 对象本身；所有对外发送都是通过 ws.conn 这个可变字段动态读取、通过 ws.answerCh
     * 这个共享队列接收回答。重连时只需复用同一个 WSSession 对象并把 conn 字段换成新连接，
     * 后台线程后续的 sendServerMsg / answerCh 操作会"无感知"地自动切换到新连接，
     * 不需要重新创建 Orchestrator 或重放整个面试流程。
     */
    private final Map<String, WSSession> userSessions = new ConcurrentHashMap<>();

    /** 重连宽限期（秒）：连接断开后，进行中的面试状态最多保留这么久等待用户重连；超时视为放弃，主动终止面试释放线程 */
    @Value("${app.interview.reconnect-grace-seconds:300}")
    private long reconnectGraceSeconds = 300;

    /** 定期清理"断线且超过宽限期未重连"的面试会话，避免线程/资源永久泄漏 */
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-reconnect-cleanup");
        t.setDaemon(true);
        return t;
    });

    /**
     * 异步任务线程池（面试流程 / 题库上传），独立于 ForkJoinPool.commonPool。
     * 面试流程内部用 Spring AI Alibaba Graph 编排，graph 的 node_async 节点会提交到 commonPool 执行；
     * 若再用 commonPool 跑 runInterview 并阻塞等待节点（interview 节点还会阻塞等用户回答），
     * 会与节点执行互相抢占 commonPool 线程，导致线程饥饿 / 死锁。故面试走独立可扩展线程池。
     */
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "interview-async-worker");
        t.setDaemon(true);
        return t;
    });

    public WebSocketHandler(Orchestrator orchestrator, ChatAgent chatAgent,
                            IntentRouter intentRouter, SkillRegistry skillRegistry,
                            DocumentLoader documentLoader, QuestionParser questionParser,
                            WebLoader webLoader, MilvusStore milvusStore,
                            BM25Manager bm25Manager, RedisStore redisStore,
                            JwtService jwtService, ChatModel chatModel, MySQLStore mysqlStore) {
        this.orchestrator = orchestrator;
        this.chatAgent = chatAgent;
        this.intentRouter = intentRouter;
        this.skillRegistry = skillRegistry;
        this.documentLoader = documentLoader;
        this.questionParser = questionParser;
        this.webLoader = webLoader;
        this.milvusStore = milvusStore;
        this.bm25Manager = bm25Manager;
        this.redisStore = redisStore;
        this.jwtService = jwtService;
        this.chatModel = chatModel;
        this.mysqlStore = mysqlStore;
        // 每 30 秒扫描一次断线未重连的面试会话，超过宽限期则主动终止，防止线程泄漏
        cleanupScheduler.scheduleWithFixedDelay(this::cleanupAbandonedSessions, 30, 30, TimeUnit.SECONDS);
    }

    /** WebSocket 会话状态 */
    private static class WSSession {
        /** 当前生效的连接；断线重连时会被替换为新连接，回调里始终按需读取该字段的最新值 */
        volatile WebSocketSession conn;
        String userID;
        /** 闲聊短期记忆：近期原始消息 + 更早消息的滚动摘要，增删逻辑由 ChatAgent 内部维护。 */
        ChatMemory chatMemory = new ChatMemory();
        Skill activeSkill;
        SkillState skillState;
        volatile BlockingQueue<String> answerCh = new LinkedBlockingQueue<>();
        volatile boolean interviewRunning = false;

        // ===== 断线重连（同后端实例内）状态快照，用于重连后回放给前端 =====
        /** 当前等待候选人作答的题号；无待答题目（如出题/评分/生成报告阶段）时为 null */
        volatile Integer pendingQuestionNum;
        /** 当前等待候选人作答的题目内容 */
        volatile String pendingQuestionContent;
        /** 该题目问出的时间点（ms），用于重连后计算"距真正超时还剩多少秒" */
        volatile long pendingQuestionAskedAtMs;
        /** 当前面试阶段（无待答题目时，重连后据此提示"正处于哪个阶段"） */
        volatile String currentStage;
        volatile String currentStageMessage;
        /** 连接断开的时间点（ms）；0 表示当前连接活跃，非 0 表示正在等待重连的宽限期内 */
        volatile long disconnectedAtMs = 0;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 从 URI query 解析 token
        String query = session.getUri() != null ? session.getUri().getQuery() : "";
        String token = "";
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    token = param.substring(6);
                    break;
                }
            }
        }

        String userID = "anonymous";
        if (!token.isEmpty()) {
            try {
                userID = jwtService.validateToken(token);
            } catch (Exception e) {
                log.warn("[WS] token 验证失败: {}", e.getMessage());
            }
        }

        // 断线重连：若该用户在 userSessions 中已有一个"仍在进行中"的面试状态，则复用同一个
        // WSSession 对象（只切换 conn 引用），让后台面试线程无感知地衔接到新连接上，
        // 而不是创建一个全新的、与运行中线程毫无关系的 WSSession。
        WSSession existing = !"anonymous".equals(userID) ? userSessions.get(userID) : null;
        boolean resumed = existing != null && existing.interviewRunning;

        WSSession ws;
        if (resumed) {
            ws = existing;
            ws.conn = session;
            ws.disconnectedAtMs = 0;
            log.info("[WS] 用户 {} 重连成功，恢复进行中的面试 (sessionId={})", userID, session.getId());
        } else {
            ws = new WSSession();
            ws.conn = session;
            ws.userID = userID;
            if (!"anonymous".equals(userID)) {
                userSessions.put(userID, ws);
            }
            log.info("[WS] 用户 {} 已连接 (sessionId={})", userID, session.getId());
        }
        sessions.put(session.getId(), ws);

        sendServerMsg(session, ServerMsg.builder().type("connected").content("连接成功").build());

        if (resumed) {
            sendResumeSnapshot(ws);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        WSSession ws = sessions.remove(session.getId());
        log.info("[WS] 连接关闭 (sessionId={})", session.getId());
        if (ws == null) return;

        // ws.conn != session 说明这个 WSSession 早已被一次重连替换成了新连接，
        // 当前这个 close 事件只是"旧连接"迟到的收尾通知，不应该影响已经生效的新连接状态。
        if (ws.conn != session) return;

        if (ws.interviewRunning) {
            // 面试仍在进行中：不立即清理状态，只记录断线时间，留出宽限期等待同一用户重连
            // （同一后端实例内）。后台面试线程仍会阻塞在 answerCh.poll() 上，不受影响；
            // 只是暂时没有连接可以推送消息 / 接收回答，直到重连或宽限期耗尽被 cleanupAbandonedSessions 清理。
            ws.disconnectedAtMs = System.currentTimeMillis();
        } else if (ws.userID != null) {
            userSessions.remove(ws.userID);
        }
    }

    /**
     * 断线重连后，把"当前等待作答的题目"（若有）连同剩余作答时间回放给前端，
     * 让用户刷新/重连页面后能立刻恢复到正确的界面状态，而不是一片空白；
     * 若当前没有待答题目（正处于出题/评分/生成报告等 LLM 处理阶段），则只提示当前阶段。
     */
    private void sendResumeSnapshot(WSSession ws) {
        Integer qNum = ws.pendingQuestionNum;
        String qContent = ws.pendingQuestionContent;
        if (qNum != null && qContent != null) {
            long elapsedSec = (System.currentTimeMillis() - ws.pendingQuestionAskedAtMs) / 1000;
            int remaining = (int) Math.max(0, answerTimeoutSeconds - elapsedSec);
            sendServerMsg(ws.conn, ServerMsg.builder()
                    .type("resumed").questionNum(qNum).content(qContent)
                    .remainingSeconds(remaining)
                    .message("已恢复连接，面试继续进行中").build());
        } else {
            sendServerMsg(ws.conn, ServerMsg.builder()
                    .type("resumed")
                    .stage(ws.currentStage)
                    .message("已恢复连接：" + (ws.currentStageMessage != null ? ws.currentStageMessage : "面试继续进行中，请稍候"))
                    .build());
        }
    }

    /**
     * 定期扫描"已断线但仍标记为进行中"的面试会话：超过重连宽限期仍未被重连认领，
     * 则视为用户已放弃，主动向 answerCh 塞入退出信号终止面试线程，避免线程/资源永久泄漏。
     */
    private void cleanupAbandonedSessions() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, WSSession> entry : userSessions.entrySet()) {
            WSSession ws = entry.getValue();
            if (ws.interviewRunning && ws.disconnectedAtMs > 0
                    && now - ws.disconnectedAtMs > reconnectGraceSeconds * 1000) {
                log.info("[WS] 用户 {} 断线超过 {} 秒未重连，主动终止其进行中的面试", entry.getKey(), reconnectGraceSeconds);
                ws.answerCh.offer("/quit");
                userSessions.remove(entry.getKey());
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WSSession ws = sessions.get(session.getId());
        if (ws == null) return;

        try {
            ClientMsg msg = objectMapper.readValue(message.getPayload(), ClientMsg.class);

            switch (msg.getType() != null ? msg.getType() : "") {
                case "chat" -> handleChat(ws, msg);
                case "start_interview" -> handleStartInterview(ws, msg);
                case "answer" -> handleAnswer(ws, msg);
                case "upload_questions" -> handleUploadQuestions(ws, msg);
                case "quit_interview" -> handleQuitInterview(ws, msg);
                case "clarify_continue" -> handleClarifyContinue(ws, msg);
                default -> sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("error")
                        .message("未知消息类型: " + msg.getType())
                        .build());
            }
        } catch (Exception e) {
            log.error("[WS] 处理消息异常: {}", e.getMessage(), e);
            sendServerMsg(session, ServerMsg.builder().type("error").message("处理消息异常: " + e.getMessage()).build());
        }
    }

    /**
     * 聊天消息处理：3 级优先级
     * 1. 已激活的 Skill 继续处理
     * 2. SkillRegistry 匹配新 Skill
     * 3. ChatAgent 兜底
     */
    private void handleChat(WSSession ws, ClientMsg msg) {
        String input = resolveInput(msg.getContent() != null ? msg.getContent() : "");

        // 优先级 1：已激活的 Skill
        if (ws.activeSkill != null) {
            if (SkillUtils.isQuitCommand(input) || (ws.skillState != null && ws.skillState.isExpired())) {
                ws.activeSkill = null;
                ws.skillState = null;
                sendServerMsg(ws.conn, ServerMsg.builder().type("chat_reply").content("已退出技能模式。").build());
                return;
            }

            // 增强（Go 版本无）：技能会话进行中，若用户又发出明确的新技能/新测验意图
            // （例如测验做到一半再说「来几道 mysql 面试题」），则结束当前会话、切换到新技能，
            // 而不是把这句话当成当前题目的回答。普通的答题内容不含触发词，不会被误切。
            if (skillRegistry.match(input) == null) {
                SkillResponse resp = ws.activeSkill.handle(input, ws.skillState);
                ws.skillState = resp.getState();
                if (resp.isDone()) {
                    ws.activeSkill = null;
                    ws.skillState = null;
                }
                sendServerMsg(ws.conn, ServerMsg.builder().type("chat_reply").content(resp.getContent()).build());
                return;
            }
            // 命中新的技能意图：清空当前会话，落到下方「优先级 2」开启新技能
            ws.activeSkill = null;
            ws.skillState = null;
        }

        // 优先级 2：匹配新 Skill
        Skill matched = skillRegistry.match(input);
        if (matched != null) {
            ws.activeSkill = matched;
            ws.skillState = SkillState.create(matched.name());
            ws.skillState.setUserId(ws.userID);

            SkillResponse resp = matched.handle(input, ws.skillState);
            ws.skillState = resp.getState();
            if (resp.isDone()) {
                ws.activeSkill = null;
                ws.skillState = null;
            }
            sendServerMsg(ws.conn, ServerMsg.builder().type("chat_reply").content(resp.getContent()).build());
            return;
        }

        // 优先级 2.5：关键词意图路由（IntentRouter）。只拦截"查看历史""上传简历/JD"这类
        // 当前系统内确有对应能力、但纯聊天走 LLM 兜底根本感知不到的意图；
        // start_interview/普通闲聊仍落到下面的 ChatAgent（其 Prompt 已经内置了引导用户点按钮的话术）。
        String intent = intentRouter.route(input, ws.interviewRunning);
        switch (intent) {
            case IntentRouter.INTENT_VIEW_HISTORY -> {
                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("chat_reply").content(buildHistoryReply(ws.userID)).build());
                return;
            }
            case IntentRouter.INTENT_UPLOAD_RESUME -> {
                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("chat_reply").content(
                                "检测到你发的像是简历文件/内容。为了走完整的简历解析 + JD 匹配度评估流程，"
                                        + "请通过页面底部的 **「开始面试」** 表单上传简历，而不是直接粘贴在聊天框里。")
                        .build());
                return;
            }
            case IntentRouter.INTENT_UPLOAD_JD -> {
                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("chat_reply").content(
                                "检测到你发的像是岗位 JD 链接/内容。请通过页面底部的 **「开始面试」** 表单提交 JD，"
                                        + "系统会自动完成抓取、分析和后续出题。")
                        .build());
                return;
            }
            default -> { /* 落到下面的 ChatAgent 兜底 */ }
        }

        // 优先级 3：ChatAgent 兜底
        String reply = chatAgent.chat(ws.chatMemory, input);
        sendServerMsg(ws.conn, ServerMsg.builder().type("chat_reply").content(reply).build());
    }

    /**
     * 组装"查看历史"意图的回复：查最近 5 场面试摘要（session_id/position/score/date），
     * 无历史时给出引导文案。数据来自 interview_records 表（MySQLStore），历史面试的完整报告/
     * 复习计划已在落库时一并保存，这里只展示摘要列表（详情建议引导到 /api/interviews 页面查看）。
     */
    private String buildHistoryReply(String userID) {
        if (mysqlStore == null) {
            return "抱歉，历史记录功能当前不可用。";
        }
        List<UserProfile.InterviewRecord> records = mysqlStore.getRecentInterviewRecords(userID, 5);
        if (records.isEmpty()) {
            return "你还没有完成过任何一场面试，点击页面底部的 **「开始面试」** 按钮开启第一场吧！";
        }
        StringBuilder sb = new StringBuilder("你最近的面试记录：\n\n");
        for (int i = 0; i < records.size(); i++) {
            UserProfile.InterviewRecord r = records.get(i);
            String dateStr = r.getDate() != null ? r.getDate().toLocalDate().toString() : "未知日期";
            sb.append(String.format("%d. **%s**（%s）—— 综合得分 %.0f\n",
                    i + 1, r.getPosition(), dateStr, r.getOverallScore()));
        }
        sb.append("\n完整报告和复习计划可以在「历史面试」页面查看详情。");
        return sb.toString();
    }

    /**
     * 开始面试
     */
    private void handleStartInterview(WSSession ws, ClientMsg msg) {
        if (ws.interviewRunning) {
            sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("面试已在进行中").build());
            return;
        }

        String jdText = msg.getJd() != null ? msg.getJd() : "";
        String resumeText = msg.getResume() != null ? msg.getResume() : "";

        if (jdText.isEmpty() || resumeText.isEmpty()) {
            sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("JD 和简历不能为空").build());
            return;
        }

        // 解析 JD / 简历输入：支持 [FILE:] 文件、URL 抓取、纯文本（与 Go resolveInput 一致）
        jdText = resolveInput(jdText);
        resumeText = resolveInput(resumeText);

        // ===== 输入质量前置校验（"用户可修复错误"）=====
        // 面试流程有 6~7 个 LLM 阶段、通常耗时数分钟。如果 JD / 简历内容本身就残缺
        // （例如 URL 抓取失败退化成一句话、文件解析出来是空白/乱码），
        // 后面所有阶段都会在残缺输入上跑一遍，最终产出的题目、评估报告质量必然很差，
        // 但要等到流程跑完才会暴露问题，代价很高。因此在真正启动流程前做一次内容长度校验，
        // 尽早把"输入质量不够"这类用户可以立即修复的问题挡在入口，而不是让它污染整条链路。
        String qualityError = validateInterviewInput(jdText, resumeText);
        if (qualityError != null) {
            sendServerMsg(ws.conn, ServerMsg.builder().type("error").message(qualityError).build());
            return;
        }

        ws.interviewRunning = true;
        ws.answerCh = new LinkedBlockingQueue<>();

        String finalJdText = jdText;
        String finalResumeText = resumeText;
        asyncExecutor.execute(() -> {
            try {
                InterviewCallbacks callbacks = new InterviewCallbacks() {
                    @Override
                    public void onStageChange(String stage, String message) {
                        // 记录快照供断线重连时回放（见 sendResumeSnapshot）
                        ws.currentStage = stage;
                        ws.currentStageMessage = message;
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("stage_change").stage(stage).message(message).build());
                    }

                    @Override
                    public void onQuestion(int questionNum, String content) {
                        // 记录快照供断线重连时回放：题号+内容+提问时间，用于恢复展示和精确倒计时
                        ws.pendingQuestionNum = questionNum;
                        ws.pendingQuestionContent = content;
                        ws.pendingQuestionAskedAtMs = System.currentTimeMillis();
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("question").questionNum(questionNum).content(content).build());
                    }

                    @Override
                    public void onQuestionDelta(int questionNum, String delta) {
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("question_delta").questionNum(questionNum).content(delta).build());
                    }

                    @Override
                    public void onScore(AnswerScore score) {
                        // 已收到评分，说明该题作答已处理完毕，清除"待答题目"快照
                        ws.pendingQuestionContent = null;
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("score")
                                .score(score.getScore())
                                .feedback(score.getFeedback())
                                .keyPointsHit(score.getKeyPointsHit())
                                .keyPointsMissed(score.getKeyPointsMissed())
                                .build());
                    }

                    @Override
                    public void onReport(String report) {
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("report").content(report).build());
                    }

                    @Override
                    public void onReviewPlan(String plan) {
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("review_plan").content(plan).build());
                    }

                    @Override
                    public void onTimeout(int questionNum) {
                        // 该题已被裁定为超时未答，清除"待答题目"快照
                        ws.pendingQuestionContent = null;
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("answer_timeout").questionNum(questionNum)
                                .message(String.format("已超过 %d 秒未作答，本题记为未回答", answerTimeoutSeconds))
                                .build());
                    }

                    @Override
                    public String getUserAnswer()
                            throws InterruptedException, UserQuitException, AnswerTimeoutException {
                        return HumanGate.await(ws.answerCh, answerTimeoutSeconds, TimeUnit.SECONDS);
                    }

                    @Override
                    public String requestClarification(String stage, String question)
                            throws InterruptedException, UserQuitException {
                        // 与 getUserAnswer 复用同一条 answerCh：前端收到 clarify_needed 后，
                        // 用户在同一个输入框里回复的内容仍然走 "answer" 消息回传，handleAnswer 无需改动。
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("clarify_needed").stage(stage).message(question).build());
                        return HumanGate.await(ws.answerCh);
                    }
                };

                orchestrator.runInterview(finalJdText, finalResumeText, ws.userID, callbacks);
            } catch (AgentCallException e) {
                // 重试耗尽后仍失败的瞬时性错误：提示用户可以直接重新开始面试再试一次，
                // 而不是笼统地报"程序异常"让用户以为是不可恢复的问题。
                log.error("[WS] 面试流程因 LLM 调用多次重试失败而中断: {}", e.getMessage(), e);
                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("error").message(e.getMessage() + "，可直接重新点击「开始面试」重试").build());
            } catch (Exception e) {
                log.error("[WS] 面试流程异常: {}", e.getMessage(), e);
                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("error").message("面试流程异常: " + e.getMessage()).build());
            } finally {
                // 与 Go 版本一致：面试流程结束后（无论正常完成、用户终止或异常）都通知前端
                sendServerMsg(ws.conn, ServerMsg.builder().type("interview_complete").build());
                ws.interviewRunning = false;
                // 面试已结束，断线重连快照不再有意义，清空避免误导后续（若该用户很快开启新面试）
                ws.pendingQuestionNum = null;
                ws.pendingQuestionContent = null;
                ws.currentStage = null;
                ws.currentStageMessage = null;
                ws.disconnectedAtMs = 0;
            }
        });
    }

    /**
     * 用户回答
     */
    private void handleAnswer(WSSession ws, ClientMsg msg) {
        if (!ws.interviewRunning) {
            sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("当前没有进行中的面试").build());
            return;
        }
        ws.answerCh.offer(msg.getContent() != null ? msg.getContent() : "");
    }

    /**
     * 上传题库
     */
    private void handleUploadQuestions(WSSession ws, ClientMsg msg) {
        String filename = msg.getFilename();
        String base64Data = msg.getData();

        if (filename == null || filename.isEmpty() || base64Data == null || base64Data.isEmpty()) {
            sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("文件名和数据不能为空").build());
            return;
        }

        asyncExecutor.execute(() -> {
            try {
                // SHA256 去重检查
                String hash = sha256(base64Data);
                String existingHash = redisStore.getFileHash(ws.userID, filename);
                if (hash.equals(existingHash)) {
                    sendServerMsg(ws.conn, ServerMsg.builder()
                            .type("upload_result")
                            .content("✅ 该题库之前已成功导入过（文件内容相同），本次自动跳过、无需重复上传，原有题目继续可用。")
                            .build());
                    return;
                }

                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("stage_change").stage("upload_parsing").message("正在解析文件内容...").build());

                // 解析文件
                String text = documentLoader.parseBase64File(filename, base64Data);

                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("stage_change").stage("upload_llm").message("正在用 LLM 提取题目...").build());

                // LLM 解析题目
                QuestionParser.ParseResult result = questionParser.parseQuestionBank(text);

                if (result.getQuestions().isEmpty()) {
                    sendServerMsg(ws.conn, ServerMsg.builder()
                            .type("upload_result")
                            .content(String.format("⚠️ 未能从该文件解析出有效题目（共识别 %d 道，均因内容过短等原因未通过校验）。请确认上传的是面试题库内容。", result.getTotal()))
                            .build());
                    return;
                }

                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("stage_change").stage("upload_indexing")
                        .message(String.format("正在写入知识库（%d 道题目）...", result.getQuestions().size()))
                        .build());

                // 先删除旧文件的题目（Milvus + BM25 两路保持一致的覆盖式更新语义，避免重复上传同一份题库时旧版本堆积）
                milvusStore.deleteBySourceFile(ws.userID, filename);
                bm25Manager.deleteBySourceFile(ws.userID, filename);

                // 写入 Milvus
                List<MilvusStore.ParsedQuestionInput> milvusQuestions = result.getQuestions().stream()
                        .map(q -> MilvusStore.ParsedQuestionInput.builder()
                                .id(q.getId())
                                .content(q.getContent())
                                .reference(q.getReference())
                                .type(q.getType())
                                .difficulty(q.getDifficulty())
                                .skills(q.getSkills())
                                .build())
                        .toList();
                milvusStore.loadParsedQuestions(ws.userID, filename, milvusQuestions);

                // 写入 BM25（补上 userId/sourceFile，供后续 deleteBySourceFile 按文件覆盖删除）
                List<RagDocument> bm25Docs = result.getQuestions().stream()
                        .map(q -> RagDocument.builder()
                                .id(q.getId())
                                .content(q.getContent() + "\n参考答案：" + q.getReference())
                                .userId(ws.userID)
                                .sourceFile(filename)
                                .build())
                        .toList();
                bm25Manager.appendDocuments(ws.userID, bm25Docs);

                // 保存文件 hash
                redisStore.saveFileHash(ws.userID, filename, hash);

                String resultMsg = String.format("✅ 题库导入成功！成功录入 %d 道题。", result.getSuccess());
                if (result.getFailed() > 0) {
                    resultMsg += String.format("\n（另有 %d 道因题目内容过短等原因被自动忽略，不影响其余题目的正常使用）", result.getFailed());
                }

                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("upload_result").content(resultMsg)
                        .message(formatParseErrors(result.getErrors())).build());
            } catch (Exception e) {
                log.error("[WS] 题库上传失败: {}", e.getMessage(), e);
                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("error").message("题库上传失败: " + e.getMessage()).build());
            }
        });
    }

    /**
     * 用户主动终止面试
     */
    private void handleQuitInterview(WSSession ws, ClientMsg msg) {
        if (ws.interviewRunning) {
            ws.answerCh.offer("/quit");
        } else {
            sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("当前没有进行中的面试").build());
        }
    }

    /**
     * 用户在 clarify_needed 追问上点击"以当前信息继续"按钮：不走自由文本输入框，直接把
     * 约定好的哨兵值（{@link HumanGate#CONTINUE_WITH_CURRENT_INFO}）塞进 answerCh，
     * 由 {@code Orchestrator.jdAnalysis} 识别后提前结束澄清循环。与 handleAnswer /
     * handleQuitInterview 复用同一条 answerCh，走同一套阻塞取值机制。
     */
    private void handleClarifyContinue(WSSession ws, ClientMsg msg) {
        if (ws.interviewRunning) {
            ws.answerCh.offer(HumanGate.CONTINUE_WITH_CURRENT_INFO);
        } else {
            sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("当前没有进行中的面试").build());
        }
    }

    /** JD 最少字符数：过短说明大概率是标题、抓取失败的残留文本或用户误粘贴，不足以支撑后续分析 */
    private static final int MIN_JD_LENGTH = 30;
    /** 简历最少字符数 */
    private static final int MIN_RESUME_LENGTH = 50;

    /**
     * 面试输入质量校验（"用户可修复错误"）：只做最基础的长度/占位符检测，
     * 不做语义判断（语义层面的缺失交给 JDAnalyzer/ResumeMatcher 结合 LLM 去发现和引导追问）。
     * 返回 null 表示通过；否则返回可直接展示给用户的、具体到"怎么改"的错误提示。
     */
    private String validateInterviewInput(String jdText, String resumeText) {
        String trimmedJD = jdText.trim();
        String trimmedResume = resumeText.trim();

        if (trimmedJD.length() < MIN_JD_LENGTH) {
            return String.format(
                    "JD 内容过短（当前 %d 字，建议不少于 %d 字），可能是链接抓取失败或粘贴不完整。"
                    + "请补充完整的岗位描述（职责、技能要求等）后重新开始面试。",
                    trimmedJD.length(), MIN_JD_LENGTH);
        }
        if (trimmedResume.length() < MIN_RESUME_LENGTH) {
            return String.format(
                    "简历内容过短（当前 %d 字，建议不少于 %d 字），可能是文件解析失败或粘贴不完整。"
                    + "请检查简历文件（如是否为扫描件 PDF）或补充完整简历文本后重新开始面试。",
                    trimmedResume.length(), MIN_RESUME_LENGTH);
        }
        return null;
    }

    /**
     * 解析输入：处理文件上传、URL 和纯文本
     */
    private String resolveInput(String content) {
        if (content == null) content = "";

        // 处理 [FILE:filename]base64data 格式
        if (content.startsWith("[FILE:")) {
            int endBracket = content.indexOf(']');
            if (endBracket > 6) {
                String filename = content.substring(6, endBracket);
                String base64Data = content.substring(endBracket + 1);
                try {
                    return documentLoader.parseBase64File(filename, base64Data);
                } catch (Exception e) {
                    log.warn("[WS] 文件解析失败: {}", e.getMessage());
                    return content;
                }
            }
        }

        // URL 检测
        if (DocumentLoader.isURL(content)) {
            try {
                return webLoader.extractJDFromURL(content);
            } catch (Exception e) {
                log.warn("[WS] URL 抓取失败: {}", e.getMessage());
                return content;
            }
        }

        return content;
    }

    private void sendServerMsg(WebSocketSession session, ServerMsg msg) {
        try {
            if (session.isOpen()) {
                String json = objectMapper.writeValueAsString(msg);
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.error("[WS] 发送消息失败: {}", e.getMessage());
        }
    }

    /** 格式化题库解析的校验失败详情（与 Go formatParseErrors 一致），无错误返回 null（NON_NULL 不序列化） */
    private String formatParseErrors(List<QuestionParser.ParseError> errs) {
        if (errs == null || errs.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (QuestionParser.ParseError e : errs) {
            sb.append(String.format("#%d: %s%n", e.getIndex(), e.getReason()));
        }
        return sb.toString();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return input.hashCode() + "";
        }
    }
}
