package com.mio.ai.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 모델별 토큰 단가. 단위는 <b>100만 토큰당 USD</b> — OpenAI 가격표 표기와 같은 단위를 써서
 * 설정값을 가격표에서 그대로 옮길 수 있게 한다.
 *
 * <p>여기 없는 모델은 비용을 <b>0 이 아니라 '미상'</b> 으로 처리한다
 * ({@link LlmCostCalculator}). 모델을 새로 도입하면서 단가 등록을 잊었을 때
 * 비용이 0 으로 집계되면 그 사실 자체를 알아챌 방법이 없기 때문이다.
 */
@ConfigurationProperties(prefix = "openai.pricing")
public class LlmPricingProperties {

    private Map<String, ModelPrice> models = new LinkedHashMap<>();

    public Map<String, ModelPrice> getModels() {
        return Map.copyOf(models);
    }

    public void setModels(Map<String, ModelPrice> models) {
        this.models = models != null ? new LinkedHashMap<>(models) : new LinkedHashMap<>();
    }

    /**
     * @param input       입력 토큰 100만 개당 USD
     * @param cachedInput 캐시 히트된 입력 토큰 100만 개당 USD. 캐시 할인이 없는 모델(임베딩 등)은 {@code null}
     * @param output      출력 토큰 100만 개당 USD
     */
    public record ModelPrice(BigDecimal input, BigDecimal cachedInput, BigDecimal output) {
        public boolean isValid() {
            return input != null && output != null
                    && input.signum() >= 0 && output.signum() >= 0
                    && (cachedInput == null || cachedInput.signum() >= 0);
        }
    }
}
