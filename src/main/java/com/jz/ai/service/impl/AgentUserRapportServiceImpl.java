// src/main/java/com/jz/ai/service/impl/AgentUserRapportServiceImpl.java
package com.jz.ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jz.ai.chat.rapport.LlmRapportScorer;
import com.jz.ai.chat.rapport.RapportScoreResult;
import com.jz.ai.chat.rapport.RapportScorerProperties;
import com.jz.ai.domain.entity.AgentUserRapport;
import com.jz.ai.domain.entity.SupportAgent;
import com.jz.ai.domain.entity.UserProfile;
import com.jz.ai.mapper.AgentUserRapportMapper;
import com.jz.ai.memory.ConversationMemoryPort;
import com.jz.ai.service.AgentUserRapportService;
import com.jz.ai.utils.PromptContextUtil;
import com.jz.ai.utils.RapportCacheRepository;
import com.jz.ai.utils.RapportPersistWorker;
import com.jz.ai.utils.UserProfileCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 引入 LLM 打分 + EWMA 稳定 + 冷却期（与越界减分不打架）
 * - 仅在“关键词触发/每N轮/最小间隔满足”时调用 LLM；
 * - 其余轮次使用锚点 + 轻量启发式；
 * - 提供 peekLastDims/notifyViolation 供外部使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentUserRapportServiceImpl
        extends ServiceImpl<AgentUserRapportMapper, AgentUserRapport>
        implements AgentUserRapportService {

    // ===== 触发关键词（可扩） =====
    private static final Pattern NEED_PATTERN   = Pattern.compile("想买|有没有|推荐|预算|便宜|贵|适合|有无|哪里|哪个好|型号|配置|尺寸|容量");
    private static final Pattern POS_PATTERN    = Pattern.compile("谢谢|不错|可以|喜欢|好呀|行|可以的|赞|麻烦你|太好了|辛苦了");
    private static final Pattern SCENE_PATTERN  = Pattern.compile("家里|孩子|爸妈|上班|加班|租房|装修|智能家居|清洁|收纳|旅行|宿舍|办公室");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    private final LlmRapportScorer llmScorer;
    private final RapportScorerProperties cfg;
    private final ConversationMemoryPort conversationMemoryPort;
    private final UserProfileCache profileCache;
    // ===== 新增依赖（缓存 + 异步持久化）=====
    private final RapportCacheRepository cacheRepo;
    private final RapportPersistWorker persistWorker;
    // 内存锚点，避免频繁落表也能稳定
    private final Map<String, Anchor> anchors = new ConcurrentHashMap<>();
    private String key(Long agentId, Long userId){ return agentId + ":" + userId; }

    private static class Anchor {
        int lastModelScore = 50;
        Map<String,Integer> lastDims = Map.of();
        LocalDateTime lastAt = LocalDateTime.MIN;
        int turnsSince = Integer.MAX_VALUE-1000;
        LocalDateTime penaltyCooldownUntil = LocalDateTime.MIN;
    }

    @Override
    public AgentUserRapport getOrInit(Long agentId, Long userId) {
//        AgentUserRapport r = this.lambdaQuery()
//                .eq(AgentUserRapport::getAgentId, agentId)
//                .eq(AgentUserRapport::getUserId, userId)
//                .one();
//        if (r == null) {
//            r = new AgentUserRapport();
//            r.setAgentId(agentId);
//            r.setUserId(userId);
//            r.setScore(50);
//            r.setTurns(0);
//            r.setSinceLastReco(999);
//            r.setLastInteractionAt(LocalDateTime.now());
//            this.save(r);
//        }
//        return r;
        return cacheRepo.getOrInit(agentId, userId);
    }


    /** 暴露“上次维度项”，给状态卡用 */
    @Override
    public Map<String,Integer> peekLastDims(Long agentId, Long userId){
        Anchor a = anchors.get(key(agentId, userId));
        return a == null ? Map.of() : (a.lastDims == null ? Map.of() : a.lastDims);
    }

    /** 越界时通知冷却（外部在 decay 后调用，避免“马上又涨回去”） */
    @Override
    public void notifyViolation(Long agentId, Long userId, Duration cooldown){
        Anchor a = anchors.computeIfAbsent(key(agentId, userId), k -> new Anchor());
        a.penaltyCooldownUntil = LocalDateTime.now().plus(cooldown);
    }
    private  String getBriefContext(String chatId, Long userId, Long agentId, int retrieveSize){
        var recent = conversationMemoryPort.fetchRecent(chatId,retrieveSize);
        String briefRecent = PromptContextUtil.recentToBullets(recent, 100);
        // 上次维度项 -> 简短行
        Map<String,Integer> lastDims = peekLastDims(agentId, userId);
        String briefDims = PromptContextUtil.lastDimsToBullets(lastDims);
        // 画像片段（最多 5 行，避开敏感项）
        UserProfile up = profileCache.toEntity(userId,profileCache.getOrLoad(userId));
        String briefProfile = PromptContextUtil.safeProfileSlice(up, 5);
        // 评分“精简上下文”
        //优化点：可以加入上一次推荐的信息？或者说成功交易信息？（得引入到正式系统）
        return PromptContextUtil.buildStateCard(briefRecent,briefDims, briefProfile);
    }
    @Override
    public int bumpOnUserUtter(String chatId,Long agentId, Long userId, String userText,int retrieveSize) {
        AgentUserRapport r = getOrInit(agentId, userId);
        String t = userText == null ? "" : userText.trim();
        int prev = r.getScore();

        // 1) 是否触发 LLM 打分
        boolean trigger = shouldTrigger(t, r);
        int modelTarget = -1;

        if (trigger) {
            try {
                // 可传简要上下文：这里用“上一分值&上一维度项”的简短提示
                String brief ="上一次评分=" + prev+"\n"+getBriefContext(chatId,userId,agentId,retrieveSize);
//                        : "上一次评分=" + prev + ", 上一次评分项=" + peekLastDims(agentId,userId);
                RapportScoreResult rs = llmScorer.score(t, brief);
                modelTarget = clamp(rs.getTargetScore(), 0, 100);

                Anchor a = anchors.computeIfAbsent(key(agentId, userId), k -> new Anchor());
                a.lastModelScore = modelTarget;
                a.lastDims = rs.getDimensions();
                a.lastAt = LocalDateTime.now();
                a.turnsSince = 0;
            } catch (Exception e) {
                log.warn("LLM rapport scoring failed, fallback heuristics. agentId={}, userId={}, err={}",
                        agentId, userId, e.toString());
            }
        } else {
            Anchor a = anchors.computeIfAbsent(key(agentId, userId), k -> new Anchor());
            a.turnsSince = Math.min(999, a.turnsSince + 1);
            if (a.lastAt != LocalDateTime.MIN) modelTarget = a.lastModelScore;
        }

        // 2) 目标值（若无模型就用启发式温和上调）
        int target = (modelTarget >= 0) ? modelTarget : heuristicTarget(prev, t);

        // 3) 平滑 + 限幅 + 死区
        int smoothed = (int)Math.round((1 - cfg.getEwmaBeta()) * prev + cfg.getEwmaBeta() * target);
        int bounded  = boundDelta(prev, smoothed, cfg.getMaxDeltaPerTurn());

        // 4) 冷却期处理（与越界惩罚不打架）
        Anchor a = anchors.computeIfAbsent(key(agentId, userId), k -> new Anchor());
        if (LocalDateTime.now().isBefore(a.penaltyCooldownUntil)) {
            if (bounded > prev) // 冷却期内最多上涨 +cap
                bounded = Math.min(prev + cfg.getCooldownRiseCap(), bounded);
        }
        int finalScore = applyDeadBand(prev, bounded, cfg.getDeadBand());
        // 5) 写回
        r.setScore(finalScore);
        r.setTurns(r.getTurns() + 1);
        r.setSinceLastReco(Math.min(999, (r.getSinceLastReco() == null ? 0 : r.getSinceLastReco()) + 1));
        r.setLastInteractionAt(LocalDateTime.now());

        cacheRepo.putCache(r);       // 刷新缓存（读多写少场景最重要的一步）
        persistWorker.offer(r);      // 异步批量写库
        return finalScore;
    }

    @Override
    public void decay(Long userId, Long agentId, int score) {
        AgentUserRapport r = getOrInit(agentId, userId);
        int newScore = Math.max(0, r.getScore() - Math.abs(score));
        r.setScore(newScore);
        // === ★ 改造点：同上，缓存 + 异步 ===
        cacheRepo.putCache(r);
        persistWorker.offer(r);
    }

    // ========= 辅助 =========

    private boolean shouldTrigger(String t, AgentUserRapport r){
        boolean lengthOk= !(t.length() < cfg.getMinLengthForModel());

        boolean keyword =
                NEED_PATTERN.matcher(t).find() ||
                        SCENE_PATTERN.matcher(t).find() ||
                        NUMBER_PATTERN.matcher(t).find() ||
                        POS_PATTERN.matcher(t).find();

        Anchor a = anchors.computeIfAbsent(key(r.getAgentId(), r.getUserId()), k -> new Anchor());
        boolean intervalOk = a.lastAt.plusSeconds(cfg.getMinIntervalSeconds()).isBefore(LocalDateTime.now());
        boolean periodic   = (r.getTurns() + 1) % cfg.getEveryNTurns() == 0;

        return (keyword && intervalOk) ||lengthOk || periodic;
    }

    private int heuristicTarget(int prev, String t){
        int delta = 0;
        if (NEED_PATTERN.matcher(t).find())  delta += 6;
        if (SCENE_PATTERN.matcher(t).find()) delta += 4;
        if (NUMBER_PATTERN.matcher(t).find())delta += 3;
        if (POS_PATTERN.matcher(t).find())   delta += 2;
        if (t.length() > 40)                 delta += 2;
        return clamp(prev + delta, 0, 100);
    }

    private int boundDelta(int prev, int now, int maxDelta){
        int d = now - prev;
        if (d >  maxDelta) return prev + maxDelta;
        if (d < -maxDelta) return prev - maxDelta;
        return now;
    }

    private int applyDeadBand(int prev, int now, int deadBand){
        return Math.abs(now - prev) < deadBand ? prev : now;
    }

    private int clamp(int v, int lo, int hi){ return Math.max(lo, Math.min(hi, v)); }
}
