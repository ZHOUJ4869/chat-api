package com.jz.ai.utils;

import java.util.Objects;

public final class ConversationIds {
    private ConversationIds() {}

    public static final String USER_PREFIX  = "u:";
    public static final String AGENT_PREFIX = "a:";

    /** user + agent 组合会话ID，例如：u:1001:a:1 */
    public static String ua(Long userId, Long agentId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(agentId, "agentId");
        return USER_PREFIX + userId + ":" + AGENT_PREFIX + agentId;
    }

    /** 仅用户（不分客服）的会话域，例如：u:1001 */
    public static String u(Long userId) {
        Objects.requireNonNull(userId, "userId");
        return USER_PREFIX + userId;
    }

    /** （可选）解析出 userId；格式不符返回 null */
    public static Long parseUserId(String convId) {
        if (convId == null || !convId.startsWith(USER_PREFIX)) return null;
        try {
            String mid = convId.substring(USER_PREFIX.length());
            int p = mid.indexOf(":");
            return Long.parseLong(p >= 0 ? mid.substring(0, p) : mid);
        } catch (Exception ignore) { return null; }
    }

    /** （可选）解析出 agentId；没有 agent 段返回 null */
    public static Long parseAgentId(String convId) {
        if (convId == null) return null;
        int idx = convId.lastIndexOf(":" + AGENT_PREFIX);
        if (idx < 0) return null;
        try {
            return Long.parseLong(convId.substring(idx + (":" + AGENT_PREFIX).length()));
        } catch (Exception ignore) { return null; }
    }
}
