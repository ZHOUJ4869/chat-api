package com.jz.ai.guard;


import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ModerationDecision {
    public enum Action { PROCEED, BOUNDARY_REPLY, SILENCE }

    private Action action;       // 继续走模型？边界提醒？沉默？
    private String replyText;    // 边界提醒时的回复文本（已做表情替换）
    private int scoreDelta;      // 对亲密度的增减（负数为降低）

    public static ModerationDecision proceed() {
        return ModerationDecision.builder().action(Action.PROCEED).scoreDelta(0).build();
    }
    public static ModerationDecision boundary(String text, int delta) {
        return ModerationDecision.builder().action(Action.BOUNDARY_REPLY).replyText(text).scoreDelta(delta).build();
    }
    public static ModerationDecision silence(int delta) {
        return ModerationDecision.builder().action(Action.SILENCE).scoreDelta(delta).build();
    }
}