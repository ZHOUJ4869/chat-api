// src/main/java/com/jz/ai/utils/PromptContextUtil.java
package com.jz.ai.utils;

import com.jz.ai.domain.entity.UserProfile;
import org.springframework.ai.chat.messages.*;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** 把“最近对话 + 合并文本 + 上次维度项 + 画像切片”拼成一段简洁上下文 */
public final class PromptContextUtil {
    private PromptContextUtil(){}

    public static String recentToBullets(List<Message> messages, int max) {
        if (messages == null || messages.isEmpty()) return "（无）";
        List<String> lines = new ArrayList<>();
        for (Message m : messages) {
            String role = (m instanceof UserMessage) ? "用户" :
                    (m instanceof AssistantMessage) ? "客服" : "系统";
            String text = Optional.ofNullable(m.getContent()).orElse("").replaceAll("\\s+"," ");
            lines.add("- " + role + "：" + text);
            if (lines.size() >= max) break;
        }
        return String.join("\n", lines);
    }

    public static String lastDimsToBullets(Map<String,Integer> dims) {
        if (dims == null || dims.isEmpty()) return "（无上次评分项）";
        List<String> keys = List.of("politeness","clarity","cooperation","purchase_intent","budget_readiness","engagement");
        return keys.stream()
                .filter(dims::containsKey)
                .map(k -> "- " + zh(k) + "：" + dims.get(k) + "/5")
                .collect(Collectors.joining("\n"));
    }

    private static String zh(String k){
        return switch (k) {
            case "politeness" -> "礼貌";
            case "clarity" -> "表达清晰";
            case "cooperation" -> "配合度";
            case "purchase_intent" -> "购买意向";
            case "budget_readiness" -> "预算明确";
            case "engagement" -> "互动度";
            default -> k;
        };
    }

    public static String safeProfileSlice(UserProfile p, int maxLines) {
        if (p == null) return "（暂无画像）";
        List<String> out = new ArrayList<>();
        if (p.getBudgetRange() != null) out.add("- 常用价位：" + p.getBudgetRange());
        if (p.getPreferredCates() != null && !p.getPreferredCates().isEmpty())
            out.add("- 常逛品类：" + String.join("、", p.getPreferredCates().stream().limit(5).toList()));
        if (p.getPreferredBrands() != null && !p.getPreferredBrands().isEmpty())
            out.add("- 品牌偏好：" + String.join("、", p.getPreferredBrands().stream().limit(5).toList()));
        if (Boolean.TRUE.equals(p.getPetOwner())) out.add("- 家有宠物");
        if (p.getHomeAreaSqm() != null) out.add("- 住房面积≈" + p.getHomeAreaSqm() + "㎡");
        if (Boolean.TRUE.equals(p.getSmartHomeIntent())) out.add("- 对智能家居有兴趣");
        if (p.getBehaviorTags() != null && !p.getBehaviorTags().isEmpty())
            out.add("- 行为标签：" + String.join("、", p.getBehaviorTags().stream().limit(5).toList()));
        if (p.getUpdatedAt() != null)
            out.add("- 画像更新时间：" + p.getUpdatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));

        if (out.isEmpty()) return "（暂无画像要点）";
        if (out.size() > maxLines) return String.join("\n", out.subList(0, maxLines));
        return String.join("\n", out);
    }

    public static String buildStateCard(String recentBullets,
                                        String lastDimsBullets,
                                        String profileSlice) {
        return """
                【会话状态卡】
                近几轮要点：
                %s
                
                上一次评分项：
                %s

                用户画像（有限要点）：
                %s
                """.formatted(
                nn(recentBullets), nn(lastDimsBullets), nn(profileSlice)
        ).trim();
    }

    private static String nn(String s){ return (s == null || s.isBlank()) ? "（无）" : s; }
}
