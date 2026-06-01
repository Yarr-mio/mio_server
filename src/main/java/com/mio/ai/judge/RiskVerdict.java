package com.mio.ai.judge;

import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.GenerationMode;

import java.util.List;

public record RiskVerdict(
        RiskLevel riskLevel,
        List<String> riskTypes,
        GenerationMode recommendedGenerationMode,
        DeliveryMode recommendedDelivery,
        boolean requireOutputSafetyGuard
) {
    public static RiskVerdict clearLow() {
        return new RiskVerdict(
                RiskLevel.CLEAR_LOW, List.of(),
                GenerationMode.NORMAL, DeliveryMode.SPECULATIVE, false
        );
    }
}
