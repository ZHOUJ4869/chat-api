package com.jz.ai.service.impl;



import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jz.ai.config.ProfileProperties;
import com.jz.ai.domain.entity.UserProfile;
import com.jz.ai.service.UserProfileService;
import com.jz.ai.utils.MoodDetector;
import com.jz.ai.utils.UserProfileCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileExtractorService {

    @Qualifier("statelessChatClients")
    private final Map<String, ChatClient> chatClientMap;
    private final ProfileProperties props;
    private final ObjectMapper mapper;
    private final UserProfileCache cache;
    private final UserProfileService userProfileService;

    private ChatClient client() {
        return Optional.ofNullable(chatClientMap.get(props.getModel()))
                .orElseGet(() -> chatClientMap.values().iterator().next());
    }

    // 在 UserProfileExtractorService 里替换 SYS 常量
    // UserProfileExtractorService 中替换 SYS
    private static final String SYS =
            "你是一个用户画像抽取器。依据“已知画像 + 最新一句用户话”，只在确有**新增或更准确信息**时产出 JSON 增量；" +
                    "不要编造，不要重复已有内容；没有新增就返回 {}。使用**中文键名**，可部分返回。" +
                    "字段：" +
                    "{ " +
                    "  \"姓名\":string, \"性别\":string, \"生日\":string, \"年龄\":number, " +
                    "  \"城市\":string, \"区域\":string, \"地址\":string, " +
                    "  \"电话\":string, \"邮箱\":string, " +
                    "  \"预算\":string, \"偏好\":[string], \"忌避\":[string], " +
                    "  \"家庭\":{ \"人数\":number, \"是否有儿童\":boolean, \"是否有老人\":boolean }, " +
                    "  \"婚姻\":string,                 " + // 单身/已婚/离异/丧偶/未知
                    "  \"子女数\":number,               " +
                    "  \"职业\":string, \"行业\":string, \"公司\":string, " +
                    "  \"学历\":string,                 " + // 高中/大专/本科/研究生/博士/其他
                    "  \"收入\":string,                 " +
                    "  \"居住状况\":string,             " + // 自有房/租房/与父母同住/宿舍
                    "  \"居住类型\":string,             " + // 公寓/别墅/合租
                    "  \"房屋面积\":number,             " +
                    "  \"是否养宠\":boolean, \"智能家居兴趣\":boolean, " +
                    "  \"品牌偏好\":[string], \"品类偏好\":[string], \"过敏源\":[string], " +
                    "  \"备注\":string " +
                    "  还可选字段（若能从本句清楚得到才输出）：\n" +
                    "  \"行为标签\":[string],   // 从 {开朗, 乐观, 外向, 内向, 冷静, 礼貌, 客气, 急躁, 直率, 幽默} 中选，最多 2 个\n" +
                    "  \"情绪\":string           // 从 {开心, 平静, 焦虑, 生气, 失望, 激动} 中选\n" +
                    "}" +
                    "注意：仅在新消息出现可用信息时输出对应键；不确定就不要输出。仅输出 JSON。";


    /** 提取增量画像（可能返回空 Map）
     *  核心抽取方法后续可能需要改进微调
     * */
    public Map<String, Object> extractDelta(Map<String, Object> current, String userUtter) {
        try {
            String cur = mapper.writeValueAsString(Optional.ofNullable(current).orElse(Collections.emptyMap()));
            String prompt = "【已知画像】\n" + cur + "\n\n【最新用户消息】\n" + userUtter + "\n\n请仅输出 JSON（如无新增返回 {}）。";

            String out = client().prompt()
                    .system(SYS)
                    .user(prompt)
                    .call()
                    .content();

            if (out == null || out.isBlank()) return Collections.emptyMap();

            // 兼容偶发包壳文本，尝试截取花括号
            int b = out.indexOf('{'), e = out.lastIndexOf('}');
            if (b >= 0 && e >= b) out = out.substring(b, e + 1);

            Map<String, Object> map = mapper.readValue(out, new TypeReference<>() {});
            return Optional.ofNullable(map).orElse(Collections.emptyMap());
        } catch (Exception e) {
            log.warn("extractDelta failed: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** 异步：抽取 + 合并 + DB & Redis 更新 */
    @Async
    public void analyzeAndUpsertAsync(Long userId, String lastUserMessage) {
        if (lastUserMessage == null || lastUserMessage.isBlank()) return;

        Map<String, Object> current = cache.getOrLoad(userId);
        Map<String, Object> delta = extractDelta(current, lastUserMessage);
        if (delta.isEmpty()) {
            // 尝试兜底情绪，仅缓存，不落库
            MoodDetector.detect(lastUserMessage).ifPresent(mood -> {
                String moodKey = props.getMoodRedisKeyPrefix() + userId;
                long ttlSecCfg = props.getMoodTtlSeconds();
                long ttlSec = Math.min(ttlSecCfg > 0 ? ttlSecCfg : 1800, 1800); // 最大 1800 秒（30 分钟）
                cache.putRaw(moodKey, "{\"情绪\":\"" + mood + "\"}", java.time.Duration.ofSeconds(ttlSec));
            });
            return;
        }
        // 把“情绪”从 delta 拆出来，仅做短期缓存（不落 DB）
        Object moodObj = delta.remove("情绪");
        Map<String, Object> merged = cache.merge(current, delta);

        // DB
        UserProfile entity = cache.toEntity(userId, merged);
        userProfileService.saveOrUpdate(entity); //可以优化为异步

        // Redis(不是删除redis，因为redis下一次很有可能会再次使用）
        cache.put(userId, merged);

        // Redis（短期情绪，1 小时 TTL）
        // Redis（短期情绪，优先用抽取的；没有再兜底）
        String mood = moodObj != null ? String.valueOf(moodObj)
                : MoodDetector.detect(lastUserMessage).orElse(null);
        if (mood != null && !mood.isBlank()) {
            String moodKey = props.getMoodRedisKeyPrefix() + userId;
            long ttlSecCfg = props.getMoodTtlSeconds();
            long ttlSec = Math.min(ttlSecCfg > 0 ? ttlSecCfg : 1800, 1800); // 最大 1800 秒（30 分钟）
            cache.putRaw(moodKey, "{\"情绪\":\"" + mood + "\"}", java.time.Duration.ofSeconds(ttlSec));
        }
    }
}

