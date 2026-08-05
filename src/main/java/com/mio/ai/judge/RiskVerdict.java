package com.mio.ai.judge;

import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.GenerationMode;

import java.util.List;

/**
 * @param crisisAttribution 위기 어휘가 누구의 것인지. 모델이 판정하지 못했으면 {@code null} 이며,
 *                          {@code null} 은 "위기 아님"이 아니라 "귀속 판정 없음"이다 (이슈 #297).
 */
public record RiskVerdict(
        RiskLevel riskLevel,
        List<String> riskTypes,
        GenerationMode recommendedGenerationMode,
        DeliveryMode recommendedDelivery,
        boolean requireOutputSafetyGuard,
        CrisisAttribution crisisAttribution
) {
    /** 귀속 판정 도입 이전 시그니처 — 기존 호출부 호환용 (이슈 #297). */
    public RiskVerdict(
            RiskLevel riskLevel,
            List<String> riskTypes,
            GenerationMode recommendedGenerationMode,
            DeliveryMode recommendedDelivery,
            boolean requireOutputSafetyGuard) {
        this(riskLevel, riskTypes, recommendedGenerationMode, recommendedDelivery,
                requireOutputSafetyGuard, null);
    }

    public static RiskVerdict clearLow() {
        return new RiskVerdict(
                RiskLevel.CLEAR_LOW, List.of(),
                GenerationMode.NORMAL, DeliveryMode.SPECULATIVE, false, null
        );
    }
}
