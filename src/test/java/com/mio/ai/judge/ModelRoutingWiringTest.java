package com.mio.ai.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmPricingProperties;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.ModelCatalog;
import com.mio.ai.llm.ModelCatalogProperties;
import com.mio.ai.llm.ModelRole;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카탈로그 override 가 실제 나가는 요청의 모델을 바꾸는지 고정한다 (#479).
 *
 * <p>카탈로그를 주입해 놓고 호출부가 계속 상수를 쓰면 "설정으로 바꿀 수 있다"는 주장이
 * 존재하지 않는 보호가 된다 — 값을 읽는 코드가 있는지는 컴파일이 아니라 이 테스트가 증명한다.
 */
class ModelRoutingWiringTest {

    private static final String OVERRIDE_MODEL = "routed-model-x";

    @Test
    @DisplayName("InputJudge 는 카탈로그가 해석한 모델로 호출한다")
    void inputJudgeUsesCatalogModel() {
        CapturingLlmClient llm = new CapturingLlmClient();
        InputJudge judge = new InputJudge(llm, new ObjectMapper(), new SimpleMeterRegistry(),
                catalogWith("input-judge"));

        judge.judge("메시지", null, null, UUID.randomUUID(), UUID.randomUUID());

        assertThat(llm.lastRequest.get().model()).isEqualTo(OVERRIDE_MODEL);
    }

    @Test
    @DisplayName("OutputJudge 는 카탈로그가 해석한 모델로 호출한다")
    void outputJudgeUsesCatalogModel() {
        CapturingLlmClient llm = new CapturingLlmClient();
        OutputJudge judge = new OutputJudge(llm, new ObjectMapper(), new SimpleMeterRegistry(),
                catalogWith("output-judge"));

        judge.judge("응답 본문", OutputPreFilterResult.pass(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(llm.lastRequest.get().model()).isEqualTo(OVERRIDE_MODEL);
    }

    @Test
    @DisplayName("CbtMetadataClassifier 는 카탈로그가 해석한 모델로 호출한다")
    void cbtClassifierUsesCatalogModel() {
        CapturingLlmClient llm = new CapturingLlmClient();
        CbtMetadataClassifier classifier = new CbtMetadataClassifier(llm, new ObjectMapper(),
                catalogWith("cbt-classifier"));

        classifier.classify(null, List.of(), "발화", "응답", null, 0, false,
                UUID.randomUUID(), UUID.randomUUID());

        assertThat(llm.lastRequest.get().model()).isEqualTo(OVERRIDE_MODEL);
    }

    // ── 픽스처 ─────────────────────────────────────────────────────

    /** 지정한 역할 하나만 {@value OVERRIDE_MODEL} 로 덮어쓴 카탈로그. */
    private static ModelCatalog catalogWith(String roleKey) {
        ModelCatalogProperties props = new ModelCatalogProperties();
        props.setRoles(Map.of(roleKey, OVERRIDE_MODEL));

        // 검증 대상이 아닌 역할 기본값(임베딩 포함)도 allowlist·단가 검증을 통과해야
        // 카탈로그가 생성된다 — 전 역할 기본값을 깔고 override 모델만 얹는다.
        List<String> models = new java.util.ArrayList<>(
                Arrays.stream(ModelRole.values()).map(ModelRole::defaultModel).distinct().toList());
        models.add(OVERRIDE_MODEL);
        props.setAllowed(models);

        LlmPricingProperties pricing = new LlmPricingProperties();
        Map<String, LlmPricingProperties.ModelPrice> table = new LinkedHashMap<>();
        for (String model : models) {
            table.put(model, new LlmPricingProperties.ModelPrice(
                    BigDecimal.ONE, null, BigDecimal.ONE));
        }
        pricing.setModels(table);
        return new ModelCatalog(props, pricing);
    }

    /** 요청을 붙잡고 무해한 JSON 을 돌려주는 가짜 클라이언트 — 모델 배선만 본다. */
    private static final class CapturingLlmClient implements LlmClient {
        final AtomicReference<LlmRequest> lastRequest = new AtomicReference<>();

        @Override
        public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
            lastRequest.set(request);
            throw new UnsupportedOperationException("이 테스트는 completeJson 경로만 쓴다");
        }

        @Override
        public String completeText(LlmRequest request) {
            lastRequest.set(request);
            return "";
        }

        @Override
        public String completeJson(LlmRequest request) {
            lastRequest.set(request);
            return "{}";
        }
    }
}
