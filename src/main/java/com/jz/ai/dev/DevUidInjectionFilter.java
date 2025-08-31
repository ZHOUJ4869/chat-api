// src/main/java/com/jz/ai/dev/DevUidInjectionFilter.java
package com.jz.ai.dev;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 开发/压测环境专用：
 * - 从请求头 X-UID 或查询参数 uid 读取用户ID
 * - 写入 HttpSession 属性 "UID"，以满足 @SessionAttribute("UID")
 * - 若还提供 X-AGENT-ID / agentId，也一并写入（可选）
 *
 * 注意：只在 dev/perf Profile 下生效，生产禁用。
 */
@Component
@Profile({"dev","perf"})
public class DevUidInjectionFilter extends OncePerRequestFilter {

    private static final String UID_KEY = "UID";
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {

        HttpSession session = req.getSession(true);

        // 如果会话里还没有 UID，就从 Header/Query 里注入
        if (session.getAttribute(UID_KEY) == null) {
            String uidStr = firstNonBlank(req.getHeader("X-UID"), req.getParameter("uid"));
            if (uidStr != null) {
                try {
                    Long uid = Long.valueOf(uidStr.trim());
                    session.setAttribute(UID_KEY, uid);
                } catch (NumberFormatException ignored) {
                    // 非法 uid，保持 null，最终会被 @SessionAttribute 报错，便于你发现问题
                }
            }
        }
        chain.doFilter(req, resp);
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }
}
