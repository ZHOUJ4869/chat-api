package com.jz.ai.guard;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Random;

@Component
@RequiredArgsConstructor
public class BoundaryReplyParaphraser {

    @Qualifier("statelessChatClients")
    private final Map<String, ChatClient> chatClients;
    private final Random rnd = new Random();

    private ChatClient client() {
        // 随便取一个最便宜模型（你也可通过配置指定）
        return Optional.ofNullable(chatClients.get("qwen-plus"))
                .orElseGet(() -> chatClients.values().iterator().next());
    }

    public String paraphrase(String base, int rapportScore) {
        try {
            String sys = """
你是真人客服。改写下面一句中文客服提醒，保持原意与边界，不超过40字，语气自然。
可微调口吻：亲密度高更轻松，低更克制。适度表情，避免重复固定句式。禁止提到AI/模型。
""";
            String prompt = "亲密度：" + rapportScore + "；原句：" + base;
            String out = client().prompt().system(sys).user(prompt).call().content();
            if (out == null || out.isBlank()) return fallback(base);
            return out.trim();
        } catch (Exception e) {
            return fallback(base);
        }
    }

    // 极简兜底：随机替换尾词/语气符号
    private String fallback(String base) {
        String[] tails = {"～", "哈", "呢", "哈～", "的", ""};
        String t = tails[rnd.nextInt(tails.length)];
        return base.replace("～", "").replace("。", "") + t;
    }
}
