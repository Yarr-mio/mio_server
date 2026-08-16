package com.mio.ai.qa;

import java.math.BigDecimal;
import java.util.List;

/**
 * Batch API 를 쓰는 <b>품질 전용</b> 스크리닝 모드 (1단계 한정).
 *
 * <h2>왜 1단계에만인가</h2>
 *
 * <p>Batch 는 입력·출력 단가를 절반으로 깎아 준다. 대신 <b>스트리밍을 지원하지 않는다.</b>
 * 그래서 이 하네스가 재는 것 중 일부는 batch 로는 존재할 수 없다.
 *
 * <ul>
 *   <li><b>지연</b> — p50/p95 와 첫 실질 토큰 시각은 스트리밍이 있어야 의미가 있다. batch 의
 *       "24시간 안에 파일로 준다" 는 제품 지연과 아무 관계가 없다.</li>
 *   <li><b>승인 단위 holdback</b> — 검증 전 노출 0 을 보장하는 전달 경로(P0-4)는 토큰이
 *       흘러가는 도중에 개입하는 구조다. 한 번에 완성본을 받으면 그 성질을 잴 대상 자체가 없다.</li>
 *   <li><b>프로덕션 경로를 그대로 태운다는 성질</b> — batch 는 "미리 생성해 두고 나중에
 *       재생한다" 는 구조를 강요한다. 이 하네스를 믿을 수 있게 만드는 것이 바로 그 성질이므로,
 *       비용을 아끼자고 그것을 내주지 않는다.</li>
 * </ul>
 *
 * <p>반대로 1단계 스크리닝의 <b>생성 품질</b> 축은 batch 로도 그대로 잰다 — 후보가 낸 응답
 * 본문을 안전·계약으로 채점하고, CBT 분류기에 태워 개입 금지 준수를 보고, 토큰을 세는 것이
 * 전부이고, 그 단계에서는 지연이 아직 기준이 아니기 때문이다.
 *
 * <p>예전 주석은 여기에 "CBT 적합" 을 적었다. <b>사실이 아니었다</b> — 그때의 그 값은 본문을
 * 보지 않고 결정론 {@code ResponsePlanner} 의 출력만 보는 값이었고(지금 이름은 플래너 계획
 * 일치율), batch 든 동기든 생성 본문과 무관하게 같은 값이 나온다. batch 로 본문을 채점할 수
 * 있는 CBT 축은 {@code CellMetrics.CBT_INTERVENTION_COMPLIANCE} 하나다.
 *
 * <h2>지금 구현된 것과 아닌 것</h2>
 *
 * <p><b>구현됨</b>: 적격성 게이트(단계·셀 제한), 지연·전달 지표를 {@link #NOT_MEASURED} 로
 * 표시하는 보고 규칙, 순위가 일부 축만 보고 매겨졌다는 진술, 견적의 batch 할인 라인,
 * 생존자용 동기 지연 프로브.
 *
 * <p><b>구현 안 됨</b>: JSONL 업로드·batch 생성·폴링·결과 다운로드 전송 계층. 그래서 실
 * LLM 실행에서 이 모드를 켜면 {@link #requireTransport} 가 <b>미구현으로 멈춘다.</b> 조용히
 * 동기 실행으로 되돌아가지 않는다 — 그러면 batch 로 돌린 줄 알고 동기 청구서를 받게 된다.
 */
final class BatchQualityMode {

    /** batch 모드에서 잴 수 없는 지표의 표기. 빈칸도 0 도 아니다. */
    static final String NOT_MEASURED =
            "미측정 (batch 모드 — 스트리밍이 없어 지연·전달 지표를 잴 수 없다)";

    /** 순위가 축을 다 못 봤다는 사실을 표에 남기는 문장. */
    static final String PARTIAL_RANKING =
            "이 순위는 지연을 뺀 축(안전·품질·비용)으로만 매겨졌다 — batch 모드에서 지연은 "
                    + "측정되지 않으므로, 지연 탈락은 별도 동기 프로브로만 판단한다";

    /** Batch API 할인율. 입력·출력 모두에 적용된다. */
    static final BigDecimal DISCOUNT = new BigDecimal("0.5");

    static final String CACHING_NOTE =
            "프롬프트 캐싱은 여기서 발동하지 않는다 — 캐시 최소 단위가 1,024 토큰인데 이 하네스의 "
                    + "케이스당 프롬프트는 ~190 토큰이다. 캐싱을 쓰려면 안정 접두사가 그 문턱을 넘도록 "
                    + "프롬프트를 재구성해야 한다 (로드맵 C-3)";

    /** batch 로 돌려도 되는 셀. 생성 품질만 보는 셀이어야 한다. */
    private static final List<BenchmarkCell> ELIGIBLE_CELLS =
            List.of(BenchmarkCell.A, BenchmarkCell.B);

    private BatchQualityMode() {
    }

    static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("mio.eval.batchQuality", "false"));
    }

    /**
     * 적격성 게이트.
     *
     * <p>조용히 무시하지 않고 멈춘다. batch 로 돌렸는데 지연 숫자가 그럴듯하게 찍혀 나오는
     * 것이 이 모드에서 가장 위험한 실패이므로, 애초에 그런 실행을 시작하지 못하게 한다.
     */
    static void requireEligible(BenchmarkStage stage, List<BenchmarkCell> cells) {
        if (stage != BenchmarkStage.SCREEN) {
            throw new IllegalStateException("""
                    batch 품질 모드는 1단계 스크리닝에서만 쓴다 (요청한 단계: %s).

                    2·3단계는 지연과 전달 경로가 판단 기준에 들어간다. batch 는 스트리밍이 없어
                    그 둘을 잴 수 없으므로, 여기서 batch 를 허용하면 '지연 없는 숫자' 가 지연을
                    본 숫자와 나란히 놓이게 된다.
                    """.formatted(stage));
        }
        List<BenchmarkCell> ineligible = cells.stream()
                .filter(cell -> !ELIGIBLE_CELLS.contains(cell))
                .toList();
        if (!ineligible.isEmpty()) {
            throw new IllegalStateException("""
                    batch 품질 모드로 돌릴 수 없는 셀이 있다: %s

                    허용: %s — 생성 품질만 보는 셀이다.
                    셀 C·D·E 는 offline reference pass·호출 수 절감·하네스 축소가 핵심이라
                    지연과 전달 경로 없이는 가설 자체를 못 본다.
                    """.formatted(ineligible, ELIGIBLE_CELLS));
        }
    }

    /**
     * 전송 계층 게이트.
     *
     * <p>이 PR 은 batch 의 <b>규칙</b>만 구현했다. 실제 제출·폴링은 없다. 없는 것을 있는 척
     * 하지 않고, 실행 시점에 멈춰서 그 사실을 말한다.
     */
    static void requireTransport(boolean stubMode) {
        if (stubMode) {
            return;
        }
        throw new IllegalStateException("""
                batch 품질 모드의 전송 계층이 아직 없다 (JSONL 업로드·batch 생성·폴링·결과 수신).

                이 PR 이 구현한 것은 적격성 게이트·보고 규칙(지연 미측정 표기)·견적의 할인 라인·
                생존자 지연 프로브까지다. 동기 실행으로 조용히 되돌아가지 않는다 — 그러면 batch 로
                돌린 줄 알고 동기 청구서를 받게 된다.

                지금 1단계를 돌리려면 이 플래그를 빼고 동기로 돌린다:
                  ./gradlew test -PllmTests -Pstage=screen -Pcells=A,B ...
                """);
    }

    /** 견적의 batch 라인. 입력·출력 모두 절반이므로 금액에 그대로 곱한다. */
    static BigDecimal discounted(BigDecimal syncUsd) {
        return syncUsd.multiply(DISCOUNT).setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
