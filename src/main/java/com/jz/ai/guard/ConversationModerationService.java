// com.jz.ai.guard.ConversationModerationService
package com.jz.ai.guard;

import com.jz.ai.config.ModerationProperties;
import com.jz.ai.utils.EmojiReplacer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConversationModerationService {

    private final ModerationProperties props;

    public ModerationDecision preModerate(String userMessage, int rapportScore,BoundaryVerdict v) {
        var level = v.getLevel();

        boolean shouldSilence =
                level == BoundaryLevel.HEAVY
                        || (level == BoundaryLevel.MID   && rapportScore < props.getMidReplyFloor())
                        || (level == BoundaryLevel.LIGHT && rapportScore < props.getLightReplyFloor());

        if (shouldSilence) {
            return ModerationDecision.silence(props.getSilenceDegradePoints());
        }

        // 分类话术（一次性拉回业务）
        String safe = null;
        if (v.getCategories().contains("privacy_personal")) safe = BoundaryReplies.privacyPersonal();
        else if (v.getCategories().contains("romantic")) safe = BoundaryReplies.romantic();
        else if (v.getCategories().contains("sexual") || v.getCategories().contains("illegal")) safe = BoundaryReplies.sexualOrIllegal();
        else if (v.getCategories().contains("profanity")) safe = BoundaryReplies.profanity();

        if (safe != null) {
            safe = EmojiReplacer.replace(safe);
            return ModerationDecision.boundary(safe, props.getBoundaryDegradePoints());
        }
        return ModerationDecision.proceed();
    }
}
