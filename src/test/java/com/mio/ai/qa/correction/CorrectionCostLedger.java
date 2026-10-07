package com.mio.ai.qa.correction;

import com.mio.ai.cost.AiCostEventWriter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 실 LLM 측정의 <b>실제 비용</b> 원장 (이슈 #552).
 *
 * <p>프로덕션 {@code OpenAiLlmClient} 는 비용 귀속 태그가 붙은 호출마다 {@link AiCostEventWriter}
 * 로 토큰과 비용을 넘긴다. 프로덕션에서는 그것이 {@code ai_cost_events} 테이블이지만, 이 원장은
 * 저장소를 쓰지 않고 메모리에만 쌓는다 — 평가 호출이 운영 비용 집계를 오염시키면 안 된다.
 *
 * <p>단가 미등록 모델의 호출은 비용이 0 이 아니라 {@code null}(미상)로 온다. 합계에서 0 으로
 * 접지 않고 {@link Totals#unpriced()} 로 따로 센다.
 */
final class CorrectionCostLedger {

    record Call(String component, String model, long promptTokens, long completionTokens,
                long cachedTokens, BigDecimal costUsd) {}

    record Totals(int calls, long promptTokens, long completionTokens, long cachedTokens,
                  BigDecimal costUsd, int unpriced) {}

    private final List<Call> calls = new CopyOnWriteArrayList<>();

    AiCostEventWriter writer() {
        return new CapturingWriter(this);
    }

    void record(Call call) {
        calls.add(call);
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    /** 컴포넌트(생성/채점)별 합계. */
    Map<String, Totals> byComponent() {
        Map<String, Totals> result = new TreeMap<>();
        for (Call call : calls) {
            result.merge(call.component(),
                    new Totals(1, call.promptTokens(), call.completionTokens(), call.cachedTokens(),
                            call.costUsd() == null ? BigDecimal.ZERO : call.costUsd(),
                            call.costUsd() == null ? 1 : 0),
                    (a, b) -> new Totals(a.calls() + b.calls(), a.promptTokens() + b.promptTokens(),
                            a.completionTokens() + b.completionTokens(),
                            a.cachedTokens() + b.cachedTokens(), a.costUsd().add(b.costUsd()),
                            a.unpriced() + b.unpriced()));
        }
        return result;
    }

    Totals total() {
        return byComponent().values().stream().reduce(
                new Totals(0, 0, 0, 0, BigDecimal.ZERO, 0),
                (a, b) -> new Totals(a.calls() + b.calls(), a.promptTokens() + b.promptTokens(),
                        a.completionTokens() + b.completionTokens(), a.cachedTokens() + b.cachedTokens(),
                        a.costUsd().add(b.costUsd()), a.unpriced() + b.unpriced()));
    }

    private static final class CapturingWriter extends AiCostEventWriter {

        private final CorrectionCostLedger ledger;

        private CapturingWriter(CorrectionCostLedger ledger) {
            super(null);
            this.ledger = ledger;
        }

        @Override
        public void write(UUID userId, UUID sessionId, String component, String model, String mode,
                          long promptTokens, long completionTokens, long cachedTokens,
                          BigDecimal costUsd, OffsetDateTime occurredAt) {
            if (component == null || component.isBlank()) {
                return;
            }
            ledger.record(new Call(component, model, promptTokens, completionTokens, cachedTokens, costUsd));
        }
    }
}
