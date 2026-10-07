package com.mio.ai.qa.correction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmCostCalculator;
import com.mio.ai.llm.ModelCatalog;
import com.mio.ai.llm.OpenAiLlmClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.net.http.HttpClient;

/**
 * 실 LLM 측정용 클라이언트 (이슈 #552). <b>프로덕션 {@code OpenAiLlmClient} 를 그대로</b> 쓴다 —
 * 재시도·속도 제한 처리·잘림 감지가 운영과 같아야 측정이 운영을 대표한다. 과금된다.
 */
final class CorrectionClients {

    private CorrectionClients() {}

    static LlmClient real(String apiKey, CorrectionCostLedger ledger) {
        return new OpenAiLlmClient(apiKey, HttpClient.newHttpClient(), new ObjectMapper(),
                new SimpleMeterRegistry(), new LlmCostCalculator(CorrectionPricing.load()),
                ledger.writer(), ModelCatalog.defaults());
    }
}
