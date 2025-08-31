// src/main/java/com/jz/ai/chat/async/BatchMerge.java
package com.jz.ai.chat.async;

import java.util.Comparator;
import java.util.List;

public final class BatchMerge {
    private BatchMerge(){}

    public static String mergeAsBullets(List<BurstBatcher.UserMsg> batch) {
        return batch.stream()
                .sorted(Comparator.comparingLong(BurstBatcher.UserMsg::getTs))
                .map(BurstBatcher.UserMsg::getText)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .reduce((a, b) -> a + "\n- " + b)
                .map(s -> "用户连续输入，请整体理解并回复：\n- " + s)
                .orElse("");
    }
}
