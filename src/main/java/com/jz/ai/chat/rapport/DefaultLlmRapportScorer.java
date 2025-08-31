// src/main/java/com/jz/ai/chat/rapport/DefaultLlmRapportScorer.java
package com.jz.ai.chat.rapport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 用 ChatClient 调模型做“文本评分”，严格要求 JSON 输出。
 * 提示词强调稳定性（避免波动大），并控制返回范围。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultLlmRapportScorer implements LlmRapportScorer {

    private final Map<String, ChatClient> chatClientMap; // 复用你的多模型池
    private final RapportScorerProperties props;
    private final ObjectMapper mapper;

    @Override
    public RapportScoreResult score(String userText, String briefContext) {
        String modelName = props.getModel();
        ChatClient client = chatClientMap.getOrDefault(modelName,
                chatClientMap.values().iterator().next());

        String sys = """
                你是稳定的会话关系评估员。请对“用户最新一轮发言”进行评分，输出严格 JSON：
                {
                  "targetScore": 0~100 的整数，表示总体亲密度/信任度目标值（越高越熟）,
                  "dimensions": {
                    "politeness":1~5,         // 礼貌
                    "clarity":1~5,            // 表达清晰
                    "cooperation":1~5,        // 配合度
                    "purchase_intent":1~5,    // 购买意向
                    "budget_readiness":1~5,   // 预算明确程度
                    "engagement":1~5          // 互动积极性
                  }
                }
                要求：
                - 避免剧烈波动，参考“简要上下文”保持稳定；除非强信号出现。
                - targetScore 只反映当前轮的合理目标，不是最终分（系统会另行平滑）。
                - 字段名与范围必须严格符合要求，禁止输出多余内容。
                """;

        String user = """
                【简要上下文】（可为空）
                %s

                【最新一轮用户发言】
                %s

                仅输出JSON。
                """.formatted(
                briefContext == null ? "" : briefContext,
                userText == null ? "" : userText
        );

        try {
            String out = client.prompt()
                    .system(sys)
                    .user(user)
                    .call().content();

            // 裁剪 { ... }
            int b = out.indexOf('{'), e = out.lastIndexOf('}');
            if (b >= 0 && e >= b) out = out.substring(b, e + 1);

            Map<String, Object> m = mapper.readValue(out, new TypeReference<>() {});
            int target = toInt(m.get("targetScore"), 50);
            if (target < 0) target = 0; if (target > 100) target = 100;

            Map<String,Integer> dims = new HashMap<>();
            Object d = m.get("dimensions");
            if (d instanceof Map<?,?> dm) {
                for (var kv : dm.entrySet()) {
                    String k = String.valueOf(kv.getKey()).toLowerCase(Locale.ROOT);
                    int v = toInt(kv.getValue(), 3);
                    if (v < 1) v = 1; if (v > 5) v = 5;
                    dims.put(k, v);
                }
            }

            RapportScoreResult r = new RapportScoreResult();
            r.setTargetScore(target);
            r.setDimensions(dims);
            return r;
        } catch (Exception e) {
            log.warn("Rapport scoring parse failed, fallback. err={}", e.toString());
            RapportScoreResult r = new RapportScoreResult();
            r.setTargetScore(50);
            r.setDimensions(Map.of());
            return r;
        }
    }

    private int toInt(Object o, int def){
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception ignore) {}
        return def;
    }
}
