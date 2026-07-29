package com.mio.ai.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 토큰 사용량을 USD 비용으로 환산한다.
 *
 * <p>환산할 수 없으면 {@code null} 을 돌려준다. 0 을 돌려주지 않는 이유는
 * {@link LlmUsage} 와 같다 — "비용이 0" 과 "비용을 모른다" 가 같은 값이 되면
 * 단가 미등록·사용량 미수신을 사후에 구분할 수 없다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmCostCalculator {

    private static final BigDecimal TOKENS_PER_PRICE_UNIT = new BigDecimal("1000000");
    /**
     * 한 호출의 비용은 아주 작을 수 있다. 임베딩 24 토큰은 $0.02/1M 기준 4.8e-7 달러라
     * 마이크로달러(1e-6)에서 끊으면 <b>0 으로 반올림돼 임베딩 비용이 영원히 0 으로 누적된다.</b>
     * 가장 싼 모델의 1 토큰(2e-8)도 살아남도록 잡는다.
     */
    private static final int COST_SCALE = 10;

    private final LlmPricingProperties pricing;
    private final Set<String> warnedModels = ConcurrentHashMap.newKeySet();

    /**
     * @return 비용(USD). 사용량을 못 받았거나 단가가 등록되지 않은 모델이면 {@code null}
     */
    public BigDecimal costUsd(LlmUsage usage) {
        if (usage == null || !usage.resolved()) {
            return null;
        }
        LlmPricingProperties.ModelPrice price = pricing.getModels().get(usage.model());
        if (price == null || !price.isValid()) {
            // 모델당 한 번만 경고한다. 매 호출마다 찍으면 요청 속도로 로그가 쏟아져
            // 정작 읽어야 할 다른 경고를 덮는다. 발생 횟수는 mio.llm.cost.unpriced 가 센다.
            if (warnedModels.add(usage.model())) {
                log.warn("LLM 단가 미등록 모델이라 비용을 계산하지 못했다: model={}. "
                        + "openai.pricing.models 에 추가해야 비용 집계가 맞는다", usage.model());
            }
            return null;
        }

        BigDecimal inputCost = price.input()
                .multiply(BigDecimal.valueOf(usage.promptTokens()))
                .divide(TOKENS_PER_PRICE_UNIT, MathContext.DECIMAL64);
        BigDecimal outputCost = price.output()
                .multiply(BigDecimal.valueOf(usage.completionTokens()))
                .divide(TOKENS_PER_PRICE_UNIT, MathContext.DECIMAL64);

        // stripTrailingZeros 로 0.0004500000 대신 0.00045 를 남긴다.
        return inputCost.add(outputCost)
                .setScale(COST_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }
}
