package com.jz.ai.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jz.ai.chat.async.BurstBatcher;
import com.jz.ai.chat.async.PendingReplyBus;
import com.jz.ai.chat.budget.BudgetService;
import com.jz.ai.chat.lms.LmsCountersService;
import com.jz.ai.chat.lms.LmsService;
import com.jz.ai.chat.lms.LmsWindowService;
import com.jz.ai.chat.log.ChatSequencer;
import com.jz.ai.chat.prompt.HumanStyleSystem;
import com.jz.ai.chat.prompt.PromptAssembler;
import com.jz.ai.common.Result;
import com.jz.ai.config.ProfileProperties;
import com.jz.ai.domain.dto.ChatDayDTO;
import com.jz.ai.domain.dto.ChatHistoryDTO;
import com.jz.ai.domain.dto.ChatMessageDTO;
import com.jz.ai.domain.dto.ChatReplyDTO;
import com.jz.ai.domain.entity.ChatMessage;
import com.jz.ai.domain.entity.Product;
import com.jz.ai.domain.entity.SupportAgent;
import com.jz.ai.domain.entity.UserProfile;
import com.jz.ai.guard.*;
import com.jz.ai.mapper.ChatMessageMapper;
import com.jz.ai.mapper.ProductMapper;
import com.jz.ai.memory.ConversationMemoryPort;
import com.jz.ai.rag.RagQueryPipeline;
import com.jz.ai.service.AgentUserRapportService;
import com.jz.ai.service.ChatLogService;
import com.jz.ai.service.SupportAgentService;
import com.jz.ai.service.impl.BehaviorTelemetryServiceImpl;
import com.jz.ai.service.impl.LmsEwmaService;
import com.jz.ai.service.impl.UserProfileExtractorService;
import com.jz.ai.utils.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
// ChatController.java 中新增字段（通过构造器注入）
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import java.util.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import jakarta.servlet.http.HttpSession;
import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;
import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY;

@Slf4j
@RestController
@RequestMapping("api/chat")
@RequiredArgsConstructor
public class ChatController {
    private final Map<String, ChatClient> chatClientMap;

    private final ChatLogService chatLogService;
    private String currentModelName = "qwen-plus"; // 默认模型
    private final ChatMessageMapper chatMessageMapper;
    private final ProductMapper productMapper;
    private final SupportAgentService agentService;
    private final AgentUserRapportService rapportService;
    private final UserProfileCache profileCache;                       // ★ 新增
    private final UserProfileExtractorService profileExtractorService; // ★ 新增
    private final ProfileProperties profileProps;                      // ★ 新增
    private final ObjectMapper mapper;                                 // ★ 新增
    // 新增注入
    private final PromptAssembler promptAssembler;
    private final LmsService lmsService;
    private final LmsCountersService lmsCountersService;
    private final BudgetService budgetService;
    private final LmsEwmaService lmsEwmaService;
    private final LmsWindowService lmsWindowService;
    private final ConversationModerationService moderationService;
    private final ConversationMemoryPort conversationMemoryPort;
    private final BehaviorTelemetryServiceImpl behaviorTelemetryService;
    private final BoundaryClassifier classifier; // ← 注入 CompositeBoundaryClassifier
    private final Window10Service window10Service;
    private final BoundaryReplyParaphraser boundaryParaphraser;
    private final RagQueryPipeline ragQueryPipeline;
    // ★异步批处理
    private final BurstBatcher burstBatcher;
    private final PendingReplyBus replyBus;
    private final ChatSequencer chatSequencer;


    private final MeterRegistry meterRegistry;
    // 初始化常用指标（构造器或@PostConstruct）
    private Timer processTimer;
    private Timer e2eTimer;
    private Counter processErrorCounter;
    private Counter replyCounter;

    @PostConstruct
    private void initMetrics() {
        this.processTimer = Timer.builder("chat.process.latency")
                .description("Latency of processBatch (merge->moderation->RAG->model->persist->push)")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        this.e2eTimer = Timer.builder("chat.e2e.first_reply")
                .description("End-to-end latency from earliest enqueue ts in batch to first reply push")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        this.processErrorCounter = Counter.builder("chat.process.error.count")
                .description("Number of exceptions thrown in processBatch")
                .register(meterRegistry);

        this.replyCounter = Counter.builder("chat.reply.count")
                .description("Number of assistant replies produced (non-silence)")
                .register(meterRegistry);
        // 可选：暴露某个 chatId 的 backlog（需要你在 /metrics 时提供 chatId tag，会很复杂）
        // 简版：只用 BurstBatcher 的 totalBacklog 全局 gauge。
    }
    @Value("${chat.lms.soft-cap-ratio}")
    private double lmsSoftCapRatio;
    @Value("${chat.lms.ewma-alpha}")
    private double ewmaAlpha;
    @Value("${chat.lms.redis.key-prefix}")
    private String lmsRedisPrefix;
    @Value("${chat.lms.redis.counter-ttl-seconds}")
    private long counterTtlSeconds;
    @Value("${chat.lms.template.max-tokens-per-item}")
    private int lmsMaxTok;
    @Value("${chat.lms.template.min-tokens-per-item}")
    private int lmsMinTok;
    @Value("${chat.emoji.max-per-message:2}")
    private int maxEmojiPerMsg;
    private Set<String> supportedModels;
    private String hotJsonGlob;
    @PostConstruct
    private void  initModels(){
        this.supportedModels=chatClientMap.keySet();
    }
    // 经验/回退值
    private static final int FALLBACK_T_HIST = 120;  // 无统计时的历史平均
    private static final int TARGET_T_LMS   = 300;   // 目标每条LMS tokens
    private static final String SESSION_AGENT_ID = "AGENT_ID_DEFAULT";

    @Value("${chat.memory.retrieve-size:40}")
    private int defaultRetrieveSize;
    /**
     * 聊天接口：使用当前模型处理请求
     */
    @Value("${chat.memory.max-messages:50}")
    private int maxMessages;

    private Long resolveAgentId(HttpSession session, Long agentIdParam) {
        if (agentIdParam != null) {
            session.setAttribute(SESSION_AGENT_ID, agentIdParam);
            return agentIdParam;
        }
        Object fromSession = session.getAttribute(SESSION_AGENT_ID);
        if (fromSession instanceof Long id) {
            return id;
        }
        // 首次进入：查一次默认客服，并存入 session
        SupportAgent agent = agentService.getDefaultAgent();
        if (agent == null) throw new IllegalStateException("没有可用客服，请先初始化 support_agent");
        Long id = agent.getId();
        session.setAttribute(SESSION_AGENT_ID, id);
        return id;
    }
    // 仅展示 chat() 方法的重要变更点
    @PostMapping
    public Result<ChatReplyDTO> chat(
            @SessionAttribute("UID") Long userId,
            @RequestBody String userMessage,
            @RequestParam(value = "history", required = false) Integer history,
            @RequestParam(value = "agentId", required = false) Long agentId,
            HttpSession session   // ← 新增
    ) throws Exception {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            String ping = EmojiReplacer.replace("我在的～这边可以帮您处理下单、售后或咨询哈。(微笑)");
            return Result.success(ChatReplyDTO.reply(ping, TypingDelayUtil.suggestDelayMs(ping, 60)));
        }
        userMessage = userMessage.trim();
        // 1) 选择客服
        // ① 只解析一次 agentId（优先URL，其次Session，最后默认客服） (session里面一般会保存当前对话的客服ID)
        Long useAgentId = resolveAgentId(session, agentId);
        SupportAgent agent = agentService.getByIdCached(useAgentId);
        if (agent == null) {
            // 若被删了，清缓存并回退到默认客服
            session.removeAttribute(SESSION_AGENT_ID);
            SupportAgent def = agentService.getDefaultAgent();
            if (def == null) return Result.error("没有可用客服，请先初始化 support_agent");
            agent = def;
            session.setAttribute(SESSION_AGENT_ID, agent.getId());
        }
        String chatId = ConversationIds.ua(userId, useAgentId);
        // ★异步：入队，1s 后连发合并
        SupportAgent finalAgent = agent;

        burstBatcher.submit(chatId, new BurstBatcher.UserMsg(userId, userMessage, System.currentTimeMillis()),
                batch -> processBatch(chatId, finalAgent, history, batch));

        // ★立刻返回一个“silence”，前端不要显示客服气泡；等 /pull 拉到再渲染
        return Result.success(ChatReplyDTO.silence());

    }
    // ========= 前端轮询拿“待送回”的客服回复 =========
    @GetMapping("/pull")
    public Result<List<ChatReplyDTO>> pull(
            @SessionAttribute("UID") Long userId,
            @RequestParam(value = "agentId", required = false) Long agentId,
            @RequestParam(defaultValue = "5") int max,
            HttpSession session
    ) {
//        SupportAgent agent = (agentId != null) ? agentService.getById(agentId) : agentService.getDefaultAgent();
//        if (agent == null) return Result.error("没有可用客服，请先初始化 support_agent");
        Long useAgentId=resolveAgentId(session,agentId);
        String chatId = ConversationIds.ua(userId, useAgentId);
        return Result.success(replyBus.pull(chatId, Math.max(1, Math.min(max, 10))));
    }
    private void processBatch(String chatId, SupportAgent agent, Integer history, List<BurstBatcher.UserMsg> batch){
        // === 指标：批中最早入队时间（用于端到端 E2E） ===
        long earliestTs = batch.stream().mapToLong(BurstBatcher.UserMsg::getTs).min().orElse(System.currentTimeMillis());//端到端指的是用户发送消息后得到回复消息的时间（process_batch+9s的等待时间+额外处理的小时间+延时（
        //延时指的就是任务因为请求量大，没有分配到线程额外等待时间）->可以比喻成你发送消息后，对方人不在的时间）
        // === 指标：processBatch 耗时 ===
        Timer.Sample processSample = Timer.start(meterRegistry); //这个processBatch函数的执行时间

        try {
            if (batch == null || batch.isEmpty()) return;
            // 2) 选择模型 & 取回条数
            // 基本上下文
            final Long userId = batch.get(0).getUserId();
            ChatClient client = chatClientMap.getOrDefault(currentModelName, chatClientMap.get("qwen-plus"));
            int retrieveSize = Math.max(0, Math.min((history != null ? history : defaultRetrieveSize), maxMessages));
            String mergedUserText = batch.stream()
                    .map(m -> "- " + m.getText())
                    .collect(Collectors.joining("\n"));
            //计算信任度（这里不是每一次都去调用模型）
            int rapport=rapportService.bumpOnUserUtter(chatId,agent.getId(),userId,mergedUserText,retrieveSize);

            // —— 分类：只对“合并文本”判一次 ——（更省）
            //边界信息定位防御（必须做的)
            BoundaryVerdict mergedVerdict = classifier.classify(mergedUserText);
           // 仅有 romantic 且级别为 LIGHT -> 允许，用来“拉近关系”
            // 轻度浪漫：LIGHT 且仅 romantic → 放行
            boolean onlyLightRomantic =
                    mergedVerdict.getLevel() == BoundaryLevel.LIGHT
                            && Optional.ofNullable(mergedVerdict.getCategories()).orElse(Set.of()).size() == 1
                            && mergedVerdict.getCategories().contains("romantic");

            // “骚扰”定义（按合并 verdict），排除 smalltalk / contact_* 与 onlyLightRomantic
            Set<String> cats = Optional.ofNullable(mergedVerdict.getCategories()).orElse(Set.of());
            boolean isHarassCat = (mergedVerdict.getLevel() != BoundaryLevel.NONE)
                    && !cats.contains("smalltalk")
                    && !cats.contains("contact_business")
                    && !cats.contains("contact_exchange")
                    && !onlyLightRomantic;
            // 取最近 N 条做短历史（建议 4~8，给RAG使用，需要再append之前取）
            List<Message> shortHistory = conversationMemoryPort.fetchRecent(chatId, Math.min(8, retrieveSize));
            // 记忆：无论如何，用户原文逐条追加（方便后续上下文）
            for (var m : batch) conversationMemoryPort.appendUser(chatId, m.getText());
            // —— 先看 10 轮窗口再标记：命中则沉默 ——（记忆/落库仍逐条记原文）
            boolean windowHasHarass = window10Service.hasHarassPrevThenMark(chatId, isHarassCat);
            if (isHarassCat && windowHasHarass) {
                // Redis 记忆：逐条写原文
                behaviorTelemetryService.recordModeration(
                        userId, chatId, mergedVerdict, ModerationDecision.Action.SILENCE,
                        "[batch] window harass", 0);
                // 落库：逐条用户
                for (var m : batch) chatLogService.persistUserAsync(chatId, userId, m.getText(),m.getTs(),chatSequencer.next(chatId));
                return;
            }
            // 4) 进入模型前做“骚扰治理”统一决策（可配置阈值/话术）
            var decision = moderationService.preModerate(mergedUserText,rapport,mergedVerdict);
            if (onlyLightRomantic && decision.getAction() != ModerationDecision.Action.PROCEED) {
                // 强制放行
                decision = ModerationDecision.builder().action(ModerationDecision.Action.PROCEED).build();
            }

            switch (decision.getAction()) {
                case SILENCE -> {
                    //也可以第一次还给回复，
                    // 低分或严重越界：沉默 + 可选降分
                    if (decision.getScoreDelta() != 0) {
                        rapportService.decay(userId, agent.getId(), Math.abs(decision.getScoreDelta()));
                        rapportService.notifyViolation(agent.getId(),userId,java.time.Duration.ofMinutes(5));
                    }
                    // 30% 概率沉默；其余发送一条边界提醒
                    boolean silence = ThreadLocalRandom.current().nextDouble() < 0.15;
                    if (silence) {
                        // 行为遥测：记录为 SILENCE
                        behaviorTelemetryService.recordModeration(
                                userId, chatId,mergedVerdict, ModerationDecision.Action.SILENCE,
                                "[batch] merged", decision.getScoreDelta()
                        );
                        for (var m : batch) chatLogService.persistUserAsync(chatId, userId, m.getText(),m.getTs(),chatSequencer.next(chatId));
                        return ;
                    } else {
                        // 发送一条固定基准话术 + 轻改写
                        String base = "抱歉，我无法处理这类内容。如需业务支持请直接说具体问题，我马上为您跟进。";
                        String safe = boundaryParaphraser.paraphrase(base, rapport);
                        // 如果你想更稳妥，可限制表情最多 1 个：safe = EmoteNormalizer.emojify(safe, 1);
                        conversationMemoryPort.appendAssistant(chatId, safe);

                        for (var m : batch) chatLogService.persistUserAsync(chatId, userId, m.getText(),m.getTs(),chatSequencer.next(chatId));
                        chatLogService.persistAssistantAsync(chatId, userId, safe, currentModelName, 0,System.currentTimeMillis(),chatSequencer.next(chatId));
                        // 行为遥测：既然发了提醒，记录为 BOUNDARY_REPLY 更准确
                        behaviorTelemetryService.recordModeration(
                                userId, chatId, mergedVerdict, ModerationDecision.Action.BOUNDARY_REPLY,
                                "[batch] merged", decision.getScoreDelta()
                        );
                        int delay = TypingDelayUtil.suggestDelayMs(safe,rapport);
                        // ★ Push 给轮询端点
                        replyBus.push(chatId, ChatReplyDTO.reply(safe, delay));
                        return ;
                    }
                }
                case BOUNDARY_REPLY -> {
                    // 轻/中度越界：边界提醒 + 可选轻微降分；不调用模型
                    if (decision.getScoreDelta() != 0) {
                        rapportService.decay(userId, agent.getId(), Math.abs(decision.getScoreDelta()));
                        rapportService.notifyViolation(agent.getId(),userId,java.time.Duration.ofMinutes(5));
                    }

                    String safe = Optional.ofNullable(decision.getReplyText()).orElseGet(() -> {
                        String base;
                        if (mergedVerdict.getCategories().contains("privacy_personal")) base = BoundaryReplies.privacyPersonal();
                        else if (mergedVerdict.getCategories().contains("romantic")) base = BoundaryReplies.romantic();
                        else if (mergedVerdict.getCategories().contains("profanity")) base = BoundaryReplies.profanity();
                        else base = BoundaryReplies.sexualOrIllegal();
                        return boundaryParaphraser.paraphrase(base, rapport); //对固定话术微调
                    });
                    // 在边界提醒分支，建议更克制：最多 1 个
                    safe = EmoteNormalizer.emojify(safe);
                    conversationMemoryPort.appendAssistant(chatId, safe);
                    for (var m : batch) chatLogService.persistUserAsync(chatId, userId, m.getText(),m.getTs(),chatSequencer.next(chatId));
                    chatLogService.persistAssistantAsync(chatId, userId, safe, currentModelName, 0,System.currentTimeMillis(),chatSequencer.next(chatId));
                    behaviorTelemetryService.recordModeration(
                            userId, chatId,
                            mergedVerdict,
                            decision.getAction(),
                            "[batch] merged",
                            decision.getScoreDelta()
                    );
                    int delay = TypingDelayUtil.suggestDelayMs(safe, rapport);
                    // ★ Push
                    replyBus.push(chatId, ChatReplyDTO.reply(safe, delay));
                    return;
                }
                case PROCEED -> {
                    // 放行，继续走模型
                }
            }
            // 5) 拟人化系统提示（隐藏AI身份，不谈模型；结合亲密度风格）
            String baseSys = agent.getSystemPrompt();
            // 若无客服名/门店，可用默认占位；避免调用不存在的 getter
            String humanSys = HumanStyleSystem.inject(baseSys, agent.getName(), agent.getStoreName(),rapport);
            // 6) 预算/提示组装（沿用你现有 PromptAssembler）
            String tone = (rapport >= 80)
                    ? "与用户关系较熟，可适度口语化与场景共鸣。"
                    : (rapport <= 40 ? "关系一般，回复更克制，避免主动推荐。" : "");
            if (onlyLightRomantic) {
                tone += " 可自然接纳对方的友好/夸赞，用一句感谢后回到业务，注意分寸。";
            }
            if(hotJsonGlob==null){
                hotJsonGlob=buildHotJson(4);
            }
            String hotJson = hotJsonGlob;
            // 可选：从用户话术里解析过滤条件（先留空）
            // 走 RAG 预检索管道，拿到候选文档
            //RAG检索必要
            List<Document> ragDocs =
                    ragQueryPipeline.searchWithAugmenters(
                            mergedUserText,
                            shortHistory);
            // 把 ragDocs 转成「参考商品 JSON」传入 PromptAssembler
            String refsJson = toReferenceJson(ragDocs,mapper,180); // 你可以按你的 JSON 结构封装（id/title/brand/price/url/snippet...）
            var asm = promptAssembler.build(
                    currentModelName, userId, chatId,
                    humanSys, hotJson,refsJson, tone,
                    retrieveSize,mergedUserText,
                    lmsSoftCapRatio, FALLBACK_T_HIST, TARGET_T_LMS,
                    lmsRedisPrefix);
            String sys = asm.systemPrompt();
            var bdg = asm.lmsBudget();
            // 手动拼上下文：System + 最近历史 + 合并后的 User
            var rr = ContinuationNudge.applyIfShortYes(chatId, mergedUserText, sys, chatMessageMapper);
            sys = rr.sys();
            mergedUserText=rr.userMessage();
            String userBatchPrompt = "以下是用户在短时间内连发的多条消息，请合并理解、一次性回复核心答案（不要逐条重复）：\n" + mergedUserText;
            List<Message> ctx = new ArrayList<>();
            ctx.add(new SystemMessage(sys));
            ctx.addAll(conversationMemoryPort.fetchRecent(chatId, retrieveSize));
            ctx.add(new UserMessage(userBatchPrompt));
            // 7) 调用模型
            long t0 = System.currentTimeMillis();

            String answer = client.prompt(new Prompt(ctx)).call().content();

            int latency = (int) (System.currentTimeMillis() - t0);
            String finalAnswer = EmoteNormalizer.emojify(answer);

            // 记忆：助手只写一条
            conversationMemoryPort.appendAssistant(chatId, finalAnswer);
            for (var m : batch) chatLogService.persistUserAsync(chatId, userId, m.getText(),m.getTs(),chatSequencer.next(chatId));
            chatLogService.persistAssistantAsync(chatId, userId,finalAnswer, currentModelName, latency,System.currentTimeMillis(),chatSequencer.next(chatId));
            // 8) 表情替换 + 打字延时建议
//        String finalAnswer= EmoteNormalizer.normalize(answer);
            // 原来：String finalAnswer = EmoteNormalizer.emojify(answer);
// 改为：
            int delayMs = TypingDelayUtil.suggestDelayMs(finalAnswer, rapport);
            e2eTimer.record(System.currentTimeMillis() - earliestTs, java.util.concurrent.TimeUnit.MILLISECONDS);
            replyCounter.increment(); // 记录一次“产生回复”
            // ★ Push：把这轮的最终客服回复放进队列，给 /pull 拉取
            replyBus.push(chatId, ChatReplyDTO.reply(finalAnswer, delayMs));
            // 9) 异步持久化与统计（保持你原有链路）
            lmsEwmaService.updateAfterTurnAsync(chatId, retrieveSize, asm.lmsInjected());
            profileExtractorService.analyzeAndUpsertAsync(userId, mergedUserText);//用户肖像的抽取。可以去掉不必要的
            // 10) 滑窗总结与压缩（LMS）（必要的，并不是每次都做）
            lmsWindowService.maybeSummarizeNextWindow(
                    chatId, userId, agent.getId(),
                    lmsRedisPrefix, counterTtlSeconds,
                    retrieveSize
            );
            lmsService.compactIfExceedAsync(chatId, bdg.getNLmsSoftCap());//（二次压缩的，不是每次都做）
        }catch (Exception e) {
            processErrorCounter.increment();
            throw new RuntimeException(e);
        }finally {
            processSample.stop(processTimer);
        }
    }
    /**
     * 设置当前模型
     */
    @PostMapping("/model")
    public ResponseEntity<String> setCurrentModel(@RequestParam String modelName) {
        if (!supportedModels.contains(modelName)) {
            return ResponseEntity.badRequest().body("模型名不支持：" + modelName);
        }
        this.currentModelName = modelName;
        return ResponseEntity.ok("已切换至模型：" + modelName);
    }

    /**
     * 获取当前模型
     */
    @GetMapping("/model")
    public String getCurrentModel() {
        return this.currentModelName;
    }

    /**
     * 获取支持的所有模型名
     */
    @GetMapping("/models")
    public Set<String> getSupportedModels() {
        return this.supportedModels;
    }

    @GetMapping("/history")
    public Result<ChatHistoryDTO> history(@SessionAttribute("UID") Long userId,
                                          @RequestParam(defaultValue = "3") int days,
                                          @RequestParam(value = "agentId", required = false) Long agentId) {
// 默认同 chat() 使用的客服
        SupportAgent agent = (agentId != null) ? agentService.getById(agentId)
                : agentService.getDefaultAgent();
        if (agent == null) return Result.error("没有可用客服，请先初始化 support_agent");

        String chatId = ConversationIds.ua(userId, agent.getId());

        // 时间范围：最近 days 天（含今天）
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate sinceDay = today.minusDays(Math.max(0, days - 1L));
        LocalDateTime since = sinceDay.atStartOfDay();

        // 拉取该时间段内的消息（升序）
        // 查询示例（MyBatis-Plus）
        List<ChatMessage> list = chatMessageMapper.selectList(
                Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getChatId, chatId)
                        .ge(ChatMessage::getCreatedAt, since)
                        .orderByAsc(ChatMessage::getSeq)           // 主排序：seq
                        .orderByAsc(ChatMessage::getCreatedAt) //这里的createdAt 时间是submit入队列时的ts，由于会发发生毫秒碰撞，所以需要seq序列号
        );
        // 转 DTO
        List<ChatMessageDTO> flat = list.stream().map(m -> {
            String from = switch (Optional.ofNullable(m.getRole()).orElse("assistant")) {
                case "assistant" -> "ai";
                case "system" -> "system";
                default -> "user";
            };
            long ts = m.getCreatedAt()
                    .atZone(zone)
                    .toInstant().toEpochMilli();
            return ChatMessageDTO.builder()
                    .from(from)
                    .text(Optional.ofNullable(m.getContent()).orElse(""))
                    .ts(ts)
                    .build();
        }).toList();

        // 按“天”分组 -> sections
        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        Map<String, List<ChatMessageDTO>> grouped = flat.stream()
                .collect(Collectors.groupingBy(
                        m -> Instant.ofEpochMilli(m.getTs()).atZone(zone).toLocalDate().format(dayFmt),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<ChatDayDTO> sections = grouped.entrySet().stream()
                .map(e -> ChatDayDTO.builder().dateLabel(e.getKey()).messages(e.getValue()).build())
                .toList();

        ChatHistoryDTO data = ChatHistoryDTO.builder()
                .days(days)
                .total(flat.size())
                .sections(sections)
                .build();

        return Result.success(data);
    }

    // ChatController 里加一个私有工具方法（或放到 util）
    private static String toReferenceJson(List<Document> docs, ObjectMapper mapper, int maxSnippetChars) {
        try {
            List<Map<String, Object>> arr = new ArrayList<>();
            for (Document d : docs) {
                Map<String, Object> m = new LinkedHashMap<>();
                Map<String, Object> meta = d.getMetadata() == null ? Map.of() : d.getMetadata();

                m.put("productId", meta.get("productId"));
                m.put("title", meta.getOrDefault("title", meta.get("name"))); // 兼容
                m.put("brand", meta.get("brand"));
                m.put("stock",meta.get("stock"));
                m.put("category", meta.get("category"));
                m.put("price", meta.get("price"));
                m.put("url", meta.get("url"));

                String content = d.getContent() == null ? "" : d.getContent().trim();
                if (maxSnippetChars > 0 && content.length() > maxSnippetChars) {
                    content = content.substring(0, maxSnippetChars) + "…";
                }
                m.put("snippet", content);

                // Document 可能带相关性分数（不同版本实现不同），有则加上
                try {
                    var score = d.getScore();
                    if (score != null) m.put("score", score);
                } catch (Throwable ignore) {}

                arr.add(m);
            }
            return mapper.writeValueAsString(arr);
        } catch (Exception e) {
            return "[]"; // 出错不阻塞主流程
        }
    }
    private String buildHotJson(int limit) {
        try {
            // 用 MyBatis-Plus 随机选 N 条（MySQL 的 RAND()，注意性能）
            var list = productMapper.selectList(
                    Wrappers.<Product>lambdaQuery()
                            .last("ORDER BY RAND() LIMIT " + limit));
            List<Map<String, Object>> arr = new ArrayList<>();
            for (Product p : list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("productId", p.getId());
                m.put("title", p.getTitle());
                m.put("brand", p.getBrand());
                m.put("stock",p.getStock());
                m.put("category", p.getCategory());
                m.put("price", p.getPrice());
                m.put("url", p.getUrl());
                // 截断描述（类似 snippet）
                String desc = Optional.ofNullable(p.getDescription()).orElse("").trim();
                if (desc.length() > 60) {
                    desc = desc.substring(0, 60) + "…";
                }
                m.put("snippet", desc);
                arr.add(m);
            }
            return mapper.writeValueAsString(arr);
        } catch (Exception e) {
            return "[]";
        }
    }
}

