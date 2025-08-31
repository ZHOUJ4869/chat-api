// com.jz.ai.utils.ContinuationNudge
package com.jz.ai.utils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jz.ai.domain.entity.ChatMessage;
import com.jz.ai.mapper.ChatMessageMapper;

import java.util.List;
import java.util.Optional;

/**
 * 将“短肯定词 + 上一轮为提议/确认型问题”改写为可执行指令，避免模型反复追问。
 */
public final class ContinuationNudge {
    private ContinuationNudge() {}

    /** 结果对象：返回（可能被追加的 sys、可能被改写的 userMessage、是否改写、上一条AI文本） */
    public static record RewriteResult(
            String sys,
            String userMessage,
            boolean rewritten,
            String lastAi
    ) {}

    /**
     * 若用户只回复“可以/好啊/行/嗯嗯/OK”等且上一轮为“邀请/提议/确认型”问题：
     *  - 给 sys 附加一段“会话锚点”提示，告诉模型直接继续上轮；
     *  - 把用户消息改写成“请直接继续上轮的推荐与讲解。”以减少歧义。
     */
    public static RewriteResult applyIfShortYes(
            String chatId,
            String userMessage,
            String sys,
            ChatMessageMapper chatMessageMapper
    ) {
        // 取上一条助手消息
        String lastAi = Optional.ofNullable(chatMessageMapper.selectList(
                        Wrappers.<ChatMessage>lambdaQuery()
                                .eq(ChatMessage::getChatId, chatId)
                                .eq(ChatMessage::getRole, "assistant")
                                .orderByDesc(ChatMessage::getCreatedAt)
                                .last("limit 1")
                ))
                .filter(list -> !list.isEmpty())
                .map((List<ChatMessage> list) -> Optional.ofNullable(list.get(0).getContent()).orElse(""))
                .orElse("");

        boolean shortYes = AffirmationIntent.isYes(userMessage);
        boolean lastWasOffer = AffirmationIntent.lastIsOffer(lastAi);
        boolean shortNo=AffirmationIntent.isNo(userMessage);
        if (shortYes && lastWasOffer) {
            String sysAddon =
                    "\n【会话锚点】用户刚刚用短语（如“可以/好啊/行”等）明确同意了你上一轮的邀请/提议。" +
                            "不要重复询问，直接执行下一步：若你刚才在推荐日系饰品并问要不要继续，就继续按上轮顺序介绍第1款；" +
                            "包含：价格区间、材质、适合场景/人群、两条搭配建议、是否有活动。120字内，口吻自然，别堆表情。";

            String newSys = (sys == null ? "" : sys) + sysAddon;
            String newUser = "（用户确认继续上一轮内容）请直接继续上轮的推荐与讲解。";
            return new RewriteResult(newSys, newUser, true, lastAi);
        }else if(shortNo){
            // 给系统一点锚点，避免模型再追问
            String anchor = "\n【会话锚点】用户明确表示不需要/先不/不了。" +
                    "不要再继续推荐或重复询问，改为：简短确认是否有其他需要，或提供替代帮助（如：活动时间、库存、售后政策）。";
            // 将 anchor 拼到 sys（你的 sys 变量命名可能是 humanSys/assembledSys）
            // 假设这里变量叫 sys：
            sys += anchor;

            // 也可以把用户输入改写为明确意图，减少歧义（可选）
            userMessage = "（用户拒绝当前提议）请不要继续推荐，转为询问是否需要其他帮助。";
        }
        return new RewriteResult(sys, userMessage, false, lastAi);
    }
}
