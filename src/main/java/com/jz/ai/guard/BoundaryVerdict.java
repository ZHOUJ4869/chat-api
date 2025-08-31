package com.jz.ai.guard;
import lombok.*;
import java.util.Set;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BoundaryVerdict {
    private BoundaryLevel level;     // NONE/LIGHT/MID/HEAVY
    private double confidence;       // 0~1
    /** 可能包含以下若干类别（多选）：
     * privacy_personal(打探客服隐私)
     * privacy_contact(要私人联系方式/私聊)
     * romantic(暧昧/搭讪)
     * smalltalk(与业务无关的闲聊)
     * sexual(露骨性内容)
     * profanity(辱骂)
     * illegal(违法)
     */
    private Set<String> categories;
    private String reason;
}
