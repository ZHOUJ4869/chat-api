package com.jz.ai.guard;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jz.ai.config.LlmModerationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

@Component
@RequiredArgsConstructor
public class LlmBoundaryClassifier implements BoundaryClassifier {

    @Qualifier("statelessChatClients")
    private final Map<String, ChatClient> chatClientMap;
    private final LlmModerationProperties props;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;

    private ChatClient client() {
        return Optional.ofNullable(chatClientMap.get(props.getModel()))
                .orElseGet(() -> chatClientMap.values().iterator().next());
    }

    private static final String SYS_SAFE = """
你是“客服合规判定”分类器。请仅根据“用户消息”输出 JSON：
{
  "level": "NONE|LIGHT|MID|HEAVY",
  "categories": [ "privacy_personal",  "romantic", "sexual", "profanity", "illegal" ],
  "confidence": 0~1,
  "reason": "简要原因"
}
判定准则（不要复述用户原文）：
- HEAVY：包含 SX 或 IL。
- MID：包含 PP / PC / PF。
- LIGHT：包含 RM / ST。
说明：SX=露骨成人向；IL=违法；PP=打探私人隐私；PC=索要私人联系方式或要求私聊；PF=辱骂；RM=暧昧/搭讪。
仅返回 JSON。
""";


    @Override
    public BoundaryVerdict classify(String userMessage) {
        if (!props.isEnabled() || userMessage == null || userMessage.isBlank()) {
            return BoundaryVerdict.builder().level(BoundaryLevel.NONE).confidence(0.0).categories(Set.of()).reason("disabled/empty").build();
        }
        String norm = userMessage.trim();
//        String key = props.getRedisKeyPrefix() + Integer.toHexString(norm.hashCode());
//        String cached = redis.opsForValue().get(key);
      /*  if (cached != null) {
            try { return mapper.readValue(cached, BoundaryVerdict.class); } catch (Exception ignore) {}
        }*/

        try {
            String out = client().prompt()
                    .system(SYS_SAFE)
                    .user("【用户消息】\n" + norm + "\n仅输出 JSON。")
                    .call()
                    .content();

            if (out == null || out.isBlank()) {
                return BoundaryVerdict.builder().level(BoundaryLevel.NONE).confidence(0.0).categories(Set.of()).reason("llm empty").build();
            }
            int b = out.indexOf('{'), e = out.lastIndexOf('}');
            if (b >= 0 && e >= b) out = out.substring(b, e + 1);

            Map<String,Object> m = mapper.readValue(out, new TypeReference<>() {});
            String levelStr = String.valueOf(m.getOrDefault("level","NONE")).toUpperCase(Locale.ROOT);
            double conf = toDouble(m.get("confidence"), .6);
            @SuppressWarnings("unchecked")
            Set<String> cats = new LinkedHashSet<>((List<String>) m.getOrDefault("categories", List.of()));
            String reason = String.valueOf(m.getOrDefault("reason",""));

            BoundaryLevel lvl = switch (levelStr) {
                case "LIGHT" -> BoundaryLevel.LIGHT;
                case "MID"   -> BoundaryLevel.MID;
                case "HEAVY" -> BoundaryLevel.HEAVY;
                default      -> BoundaryLevel.NONE;
            };
            BoundaryVerdict v = BoundaryVerdict.builder().level(lvl).confidence(conf).categories(cats).reason(reason).build();

//            redis.opsForValue().set(key, mapper.writeValueAsString(v), Duration.ofSeconds(props.getCacheTtlSeconds()));
            return v;
        } catch (Exception e) {
            return BoundaryVerdict.builder().level(BoundaryLevel.NONE).confidence(0.0).categories(Set.of()).reason("llm error").build();
        }
    }

    private static double toDouble(Object v, double d) {
        try { return v == null ? d : Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return d; }
    }
}
