// src/main/java/com/jz/ai/chat/rapport/LlmRapportScorer.java
package com.jz.ai.chat.rapport;

/** LLM 打分器：输入本轮文本 +（可选）简要上下文，输出 0~100 和各维 1~5 */
public interface LlmRapportScorer {
    RapportScoreResult score(String userText, String briefContext);
}
