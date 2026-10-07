package com.mio.ai.qa.correction;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 실 LLM 측정의 <b>비용 상한</b> (이슈 #552). 사용자가 정한 금액을 코드가 두 번 지킨다.
 *
 * <ol>
 *   <li><b>실행 전:</b> 견적의 <b>상한값</b>(가장 나쁜 경우)이 예산을 넘으면 호출을 시작하지 않는다.
 *       기대값이 아니라 상한값으로 막는 이유는, 견적이 근사여서 상한 쪽으로 읽어야 안전하기 때문이다.</li>
 *   <li><b>실행 중:</b> 제공자가 보고한 실제 비용 합계가 예산을 넘으면 남은 호출을 시작하지 않는다.
 *       이미 진행 중인 호출은 끝나므로 상한을 병렬도만큼 넘을 수 있다.</li>
 * </ol>
 *
 * <p>금액은 원화로 받아 달러로 환산한다 (환율은 설정값, 기본 {@value #DEFAULT_KRW_PER_USD}원). 실시간 환율이
 * 아니므로 실제 청구 금액과 다를 수 있다.
 */
final class CorrectionBudget {

    static final int DEFAULT_KRW_PER_USD = 1400;

    private final long maxKrw;
    private final int krwPerUsd;
    private final BigDecimal maxUsd;

    private CorrectionBudget(long maxKrw, int krwPerUsd) {
        this.maxKrw = maxKrw;
        this.krwPerUsd = krwPerUsd;
        this.maxUsd = BigDecimal.valueOf(maxKrw).divide(BigDecimal.valueOf(krwPerUsd), 6, RoundingMode.HALF_UP);
    }

    static CorrectionBudget ofKrw(long maxKrw, int krwPerUsd) {
        if (maxKrw < 1) {
            throw new IllegalArgumentException("비용 상한은 1원 이상이어야 한다: " + maxKrw);
        }
        if (krwPerUsd < 1) {
            throw new IllegalArgumentException("환율은 1 이상이어야 한다: " + krwPerUsd);
        }
        return new CorrectionBudget(maxKrw, krwPerUsd);
    }

    BigDecimal maxUsd() {
        return maxUsd;
    }

    /** 실행 중 확인: 지금까지의 실제 비용이 예산을 넘었는가. */
    boolean exceeded(CorrectionCostLedger ledger) {
        return ledger.total().costUsd().compareTo(maxUsd) > 0;
    }

    /**
     * 실행 전 확인: 견적을 계산하지 못했거나(단가 미등록) 상한값이 예산을 넘으면 실행하지 않는다.
     *
     * @throws IllegalStateException 실행하면 안 되는 이유를 담는다
     */
    void requireEstimateWithin(CorrectionCostEstimator.Estimate estimate) {
        if (!estimate.priced()) {
            throw new IllegalStateException("단가 미등록 모델 %s 가 있어 비용 견적을 낼 수 없다 — 예산을 지킬 수 없으므로 "
                    .formatted(estimate.unpricedModels()) + "실행하지 않는다");
        }
        if (estimate.highUsd().compareTo(maxUsd) > 0) {
            throw new IllegalStateException(
                    "견적 상한 $%s(약 %d원)이 예산 %d원($%s)을 넘는다 — 케이스 수·반복·모드·팔을 줄이거나 예산을 조정한다"
                            .formatted(estimate.highUsd().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                    estimate.highUsd().multiply(BigDecimal.valueOf(krwPerUsd))
                                            .setScale(0, RoundingMode.HALF_UP).longValue(),
                                    maxKrw, maxUsd.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        }
    }

    String describe() {
        return "예산 %d원 (환율 %d원/$ 가정 → $%s)".formatted(maxKrw, krwPerUsd,
                maxUsd.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }
}
