package com.jz.ai.service;
import com.jz.ai.guard.BoundaryVerdict;
import com.jz.ai.guard.ModerationDecision.Action;
public interface BehaviorTelemetryService {
    void recordModeration(Long userId, String chatId,
                          BoundaryVerdict verdict,
                          Action action, String userMessage, int scoreDelta);
}
