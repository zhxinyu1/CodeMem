package com.codemem.extraction;

import com.codemem.outbox.TurnCompletedPayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 抽取器切换：默认规则版；`CODEMEM_EXTRACTOR=llm` 时用结构化输出抽取。
 * LLM 版内部保留规则版兜底，Key 缺失或调用异常都不会阻塞 Outbox。
 */
@Component
@Primary
public class ExtractorRouter implements TurnExtractor {

    private final RuleBasedTurnExtractor ruleExtractor;
    private final LlmTurnExtractor llmExtractor;
    private final boolean useLlm;

    public ExtractorRouter(RuleBasedTurnExtractor ruleExtractor,
                           LlmTurnExtractor llmExtractor,
                           @Value("${codemem.extractor:rule}") String mode) {
        this.ruleExtractor = ruleExtractor;
        this.llmExtractor = llmExtractor;
        this.useLlm = "llm".equalsIgnoreCase(mode);
    }

    @Override
    public TurnExtractionResult extract(TurnCompletedPayload payload) {
        return useLlm ? llmExtractor.extract(payload) : ruleExtractor.extract(payload);
    }

    public String mode() {
        return useLlm ? "llm" : "rule";
    }
}