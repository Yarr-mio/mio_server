package com.mio.ai.qa;

import com.mio.ai.cost.AiCostEventWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 호출별 토큰·비용 원장.
 *
 * <p>§11.3 은 셀마다 "호출 수, prompt/completion token, 수용 가능한 응답당 원가" 를 내라고
 * 한다. 그런데 {@code LlmClient.completeJson/completeText} 는 사용량을 반환하지 않는다 —
 * 판정 호출의 토큰 수를 호출부에서 셀 방법이 없다.
 *
 * <p><b>프로덕션을 고치지 않고 seam 을 얻는다.</b> {@code OpenAiLlmClient.recordUsage()} 는
 * stream·complete·embed 세 진입점이 모두 지나는 단 한 곳이고, 거기서
 * {@link AiCostEventWriter#write} 를 호출한다. 그 타입을 테스트에서 상속해 호출을 가로채면
 * 모든 호출의 model·mode·토큰·비용이 그대로 들어온다. 프로덕션 코드는 한 줄도 바뀌지 않고,
 * 벤치마크가 보는 값과 운영이 청구서로 받는 값이 같은 계산에서 나온다.
 *
 * <p>케이스 귀속은 {@code LlmRequest.userId} 로 한다. 하네스가 케이스마다 결정론적 UUID 를
 * 넣고, 원장이 그 값으로 묶는다. 병렬 실행에서도 스레드가 아니라 요청이 케이스를 들고 다니므로
 * 귀속이 어긋나지 않는다.
 *
 * <p>사용량을 받지 못한 호출({@code resolved=false})은 여기 도달하지 않는다 —
 * {@code recordUsage} 가 그 앞에서 돌아간다. 그래서 그 수는 미터 레지스트리에서 따로 읽는다
 * ({@link #usageMissingCalls()}). 0 으로 접으면 "토큰을 안 썼다" 와 "얼마나 썼는지 모른다" 가
 * 같은 값이 된다.
 */
final class CellTokenLedger {

    /**
     * LLM 호출 한 건.
     *
     * @param costUsd 단가 미등록이면 {@code null} — 0 이 아니다
     */
    record Call(UUID caseKey, String component, String model, String mode,
                long promptTokens, long completionTokens, long cachedTokens, BigDecimal costUsd) {

        boolean priced() {
            return costUsd != null;
        }
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<UUID, String> caseIds = new ConcurrentHashMap<>();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    /** 프로덕션 {@code OpenAiLlmClient} 에 그대로 넣는 기록 지점. 저장소는 쓰지 않는다. */
    AiCostEventWriter writer() {
        return new CapturingWriter(this);
    }

    /** 프로덕션 컴포넌트에 넘길 미터 레지스트리. 호출 결과·사용량 미수신을 여기서 읽는다. */
    SimpleMeterRegistry meterRegistry() {
        return meterRegistry;
    }

    /** 케이스 ID ↔ 상관 UUID 를 고정한다. 같은 케이스는 항상 같은 UUID 를 받는다. */
    UUID keyFor(String caseId) {
        UUID key = UUID.nameUUIDFromBytes(caseId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        caseIds.put(key, caseId);
        return key;
    }

    void record(Call call) {
        calls.add(call);
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    List<Call> callsFor(UUID caseKey) {
        return calls.stream().filter(c -> caseKey.equals(c.caseKey())).toList();
    }

    long promptTokens() {
        return calls.stream().mapToLong(Call::promptTokens).sum();
    }

    long completionTokens() {
        return calls.stream().mapToLong(Call::completionTokens).sum();
    }

    /**
     * 전체 비용.
     *
     * @return 단가 미등록 호출이 하나라도 있으면 {@link Optional#empty()} — 아는 부분만 더해
     *         "총액" 이라고 부르면 그건 실제보다 작은 수를 총액이라고 부르는 것이다
     */
    Optional<BigDecimal> totalCostUsd() {
        if (calls.stream().anyMatch(call -> !call.priced())) {
            return Optional.empty();
        }
        return Optional.of(calls.stream()
                .map(Call::costUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** 단가가 등록되지 않아 비용을 모르는 호출 수. */
    long unpricedCalls() {
        return calls.stream().filter(call -> !call.priced()).count();
    }

    /** 사용량 자체를 받지 못한 호출 수 ({@code mio.llm.usage outcome=missing}). */
    long usageMissingCalls() {
        return (long) counterSum("mio.llm.usage", "outcome", "missing");
    }

    /** 외부 호출 실패 수 — success 가 아닌 모든 결과({@code mio.llm.requests}). */
    long externalFailureCalls() {
        double total = counterSum("mio.llm.requests", null, null);
        double success = counterSum("mio.llm.requests", "outcome", "success");
        return Math.round(total - success);
    }

    /** 파싱 단계까지 포함한 판정 실패 수. */
    long judgeFailures() {
        return Math.round(counterSum("mio.judge.input", "outcome", "failed")
                + counterSum("mio.judge.output", "outcome", "failed"));
    }

    private double counterSum(String name, String tagKey, String tagValue) {
        return meterRegistry.find(name).counters().stream()
                .filter(counter -> tagKey == null
                        || tagValue.equals(counter.getId().getTag(tagKey)))
                .mapToDouble(Counter::count)
                .sum();
    }

    /**
     * {@link AiCostEventWriter} 를 상속해 저장 대신 수집한다.
     *
     * <p>{@code super(null)} 로 저장소를 비운다 — 이 구현은 상위 메서드를 호출하지 않으므로
     * 저장소를 절대 건드리지 않는다. DB 없이 도는 것이 목적이다.
     */
    private static final class CapturingWriter extends AiCostEventWriter {

        private final CellTokenLedger ledger;

        private CapturingWriter(CellTokenLedger ledger) {
            super(null);
            this.ledger = ledger;
        }

        @Override
        public void write(UUID userId, UUID sessionId, String component, String model, String mode,
                          long promptTokens, long completionTokens, long cachedTokens,
                          BigDecimal costUsd, OffsetDateTime occurredAt) {
            ledger.record(new Call(userId, component, model, mode,
                    promptTokens, completionTokens, cachedTokens, costUsd));
        }
    }
}
