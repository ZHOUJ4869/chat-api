package com.jz.ai.guard;



import com.jz.ai.config.LlmModerationProperties;
import lombok.RequiredArgsConstructor;
import org.apache.http.impl.nio.reactor.ExceptionEvent;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@RequiredArgsConstructor
public class CompositeBoundaryClassifier implements BoundaryClassifier {

    private final HeuristicBoundaryClassifier heuristic;
    private final LlmBoundaryClassifier llm;
    private final LlmModerationProperties props;

    @Override
    public BoundaryVerdict classify(String userMessage) {
        BoundaryVerdict h = heuristic.classify(userMessage);
        // 明显 HEAVY/MID 直接返回
        if (h.getLevel() == BoundaryLevel.HEAVY || h.getLevel() == BoundaryLevel.MID) return h;
        //先用一般方法判断比较快、分辨不出来再用llm兜底
        // 边缘情况才调用 LLM；置信度达阈值才覆盖
        try{
            if (props.isEnabled()) {
                BoundaryVerdict v = llm.classify(userMessage);
                if (v.getConfidence() >= props.getEscalateThreshold()) return v;
            }
        }catch (Exception ignored){

        }
        return h;
    }
}
