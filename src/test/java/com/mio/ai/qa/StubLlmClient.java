package com.mio.ai.qa;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Consumer;

/**
 * 네트워크를 타지 않는 {@link LlmClient}. 비용 추정과 하네스 자체 검증에 쓴다.
 *
 * <p>목적이 둘이다.
 *
 * <ol>
 *   <li><b>실행 전 견적.</b> 실제 프롬프트를 그대로 만들어 보내는 지점까지 코드를 태우고,
 *       거기서 토큰 수를 추정한다. 프롬프트를 손으로 다시 적어 재는 방식이 아니라
 *       프로덕션이 실제로 조립한 프롬프트를 재므로, 프롬프트가 길어지면 견적도 같이 는다.</li>
 *   <li><b>키 없는 검증.</b> API 키가 없어도 하네스 전 구간이 도는지 확인할 수 있다.</li>
 * </ol>
 *
 * <p><b>이 클라이언트로 낸 수치는 판정에 쓸 수 없다.</b> 판정 내용이 고정값이므로 안전
 * 지표는 모델 성능이 아니라 스텁의 설정을 재는 것이 된다. {@link CellRunner} 가 스텁 실행에
 * 대해 아카이브 기록과 Go/No-Go 산출을 모두 막는다.
 */
final class StubLlmClient implements LlmClient {

    /** 스텁 판정 — 항상 같은 값이다. 이 값으로 안전 지표를 주장할 수 없다는 뜻이기도 하다. */
    private static final String INPUT_JUDGE_JSON = """
            {"security":{"level":"CLEAN","attack_types":[],"require_output_security_guard":false},
             "risk":{"risk_level":"CLEAR_LOW","risk_types":[],"crisis_attribution":"NONE",
                     "recommended_generation_mode":"NORMAL","recommended_delivery":"SPECULATIVE",
                     "require_output_safety_guard":false},
             "confidence":0.9}
            """;

    private static final String OUTPUT_JUDGE_JSON = "{\"action\":\"SEND\"}";

    /**
     * 생성 스텁 응답.
     *
     * <p>길이만 의미가 있다 — 견적의 completion 토큰이 여기서 나온다. 실제 응답 길이의
     * 상한은 {@code ConversationOrchestrator.LLM_MAX_COMPLETION_TOKENS}(400) 이고, 실측
     * 평균은 그보다 짧다. 견적이 낙관적이지 않도록 상한에 가까운 길이를 쓴다.
     */
    private static final String GENERATION_TEXT = "그렇게 느끼셨군요. ".repeat(30);

    private final CellTokenLedger ledger;
    private final CellPricingBook pricing;

    StubLlmClient(CellTokenLedger ledger, CellPricingBook pricing) {
        this.ledger = ledger;
        this.pricing = pricing;
    }

    @Override
    public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
        chunkHandler.accept(GENERATION_TEXT);
        long completion = CellTokenEstimator.tokens(GENERATION_TEXT);
        record(request, "stream", completion);
        return new LlmStreamResult(0L,
                LlmUsage.of(request.model(), CellTokenEstimator.promptTokens(request.messages()),
                        completion),
                false);
    }

    @Override
    public String completeText(LlmRequest request) {
        record(request, "complete_text", CellTokenEstimator.tokens(GENERATION_TEXT));
        return GENERATION_TEXT;
    }

    @Override
    public String completeJson(LlmRequest request) {
        String response = "OUTPUT_JUDGE".equals(request.component())
                ? OUTPUT_JUDGE_JSON
                : INPUT_JUDGE_JSON;
        record(request, "complete_json", CellTokenEstimator.tokens(response));
        return response;
    }

    /**
     * 프로덕션의 {@code recordUsage()} 와 같은 자리에 같은 값을 남긴다.
     *
     * <p>견적과 실측이 <b>같은 집계 코드</b>를 지나야, 견적이 틀렸을 때 그 차이가 집계
     * 방식의 차이가 아니라 토큰 추정의 차이임을 알 수 있다.
     */
    private void record(LlmRequest request, String mode, long completionTokens) {
        long promptTokens = CellTokenEstimator.promptTokens(request.messages());
        ledger.writer().write(request.userId(), request.sessionId(), request.component(),
                request.model(), mode, promptTokens, completionTokens, 0L,
                pricing.costUsd(request.model(), promptTokens, completionTokens, 0L).orElse(null),
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
