package com.mio.ai.orchestrator;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmPricingProperties;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.ModelCatalog;
import com.mio.ai.llm.ModelCatalogProperties;
import com.mio.ai.llm.ModelRole;

import com.mio.ai.judge.OutputPreFilter;
import com.mio.ai.judge.OutputPreFilterResult;
import com.mio.ai.memory.consolidation.MemoryConsentChecker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * shadow 실행기 (#481).
 *
 * <p>안전 성질 둘을 고정한다. <b>본 응답 경로에 아무 비용도 지연도 더하지 않는다</b> —
 * LLM 호출·동의 조회는 전부 비동기 태스크 안에서 일어나고, 제출 실패·설정 오류·Redis 장애는
 * 삼켜지고 계측만 남는다. 그리고 <b>미검증 모델·동의 철회 사용자에게는 절대 나가지 않는다</b> —
 * allowlist·단가·동의 게이트가 canary 와 같은 fail-safe 로 걸린다.
 */
class ShadowGenerationRunnerTest {

    private static final String SHADOW_MODEL = "gpt-4.1-nano";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SimpleMeterRegistry meters;
    private RecordingLlmClient llm;
    private OutputPreFilter preFilter;
    private MemoryConsentChecker consent;
    private List<Runnable> submitted;
    private ShadowGenerationRunner runner;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        meters = new SimpleMeterRegistry();
        llm = new RecordingLlmClient("안전한 그림자 응답입니다.");
        preFilter = mock(OutputPreFilter.class);
        when(preFilter.check(anyString())).thenReturn(OutputPreFilterResult.pass());
        consent = mock(MemoryConsentChecker.class);
        when(consent.isRetentionAllowed(any())).thenReturn(true);
        submitted = new ArrayList<>();
        runner = new ShadowGenerationRunner(catalogAllowing(SHADOW_MODEL), redis, meters,
                llm, preFilter, consent, submitted::add);
    }

    @Test
    @DisplayName("설정이 없으면 아무것도 하지 않는다 — 제출도 호출도 없다")
    void noConfigMeansNoShadow() {
        when(values.get(anyString())).thenReturn(null);

        runner.maybeShadow(primaryRequest());

        assertThat(submitted).isEmpty();
        assertThat(llm.lastRequest.get()).isNull();
    }

    @Test
    @DisplayName("샘플된 턴은 그림자 모델·SHADOW_GENERATION 귀속으로 복제 호출된다")
    void sampledTurnRunsShadowWithShadowModelAndComponent() {
        when(values.get(anyString())).thenReturn(SHADOW_MODEL + " 100");
        LlmRequest primary = primaryRequest();

        runner.maybeShadow(primary);
        assertThat(llm.lastRequest.get())
                .as("제출 시점에는 아직 호출이 없어야 한다 — 호출은 비동기 태스크의 몫이다")
                .isNull();
        submitted.forEach(Runnable::run);

        LlmRequest shadow = llm.lastRequest.get();
        assertThat(shadow.model()).isEqualTo(SHADOW_MODEL);
        assertThat(shadow.component()).isEqualTo("SHADOW_GENERATION");
        assertThat(shadow.messages()).isEqualTo(primary.messages());
        assertThat(shadow.maxCompletionTokens()).isEqualTo(primary.maxCompletionTokens());
        assertThat(meters.counter("mio.model.shadow", "outcome", "prefilter_passed").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("동의 철회 사용자는 그림자 대상이 아니다 — 조회는 비동기에서, 실패는 제외로")
    void consentWithdrawnUsersAreExcluded() {
        when(values.get(anyString())).thenReturn(SHADOW_MODEL + " 100");
        when(consent.isRetentionAllowed(any())).thenReturn(false);

        runner.maybeShadow(primaryRequest());
        submitted.forEach(Runnable::run);

        assertThat(llm.lastRequest.get())
                .as("추가 전송은 필수 아님 — 동의 없이 나가면 P0-6 게이트를 우회한다")
                .isNull();
        assertThat(meters.counter("mio.model.shadow", "outcome", "consent_excluded").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("percent 0 · allowlist 밖 · 단가 미등록 · 깨진 설정은 전부 무시된다")
    void invalidOrDisabledConfigIsIgnored() {
        for (String config : new String[]{SHADOW_MODEL + " 0", "model-nobody-approved 100",
                "allowed-but-unpriced 100", SHADOW_MODEL + " abc", SHADOW_MODEL + " 101"}) {
            when(values.get(anyString())).thenReturn(config);
            runner.maybeShadow(primaryRequest());
        }

        assertThat(submitted).isEmpty();
        assertThat(llm.lastRequest.get()).isNull();
        assertThat(meters.counter("mio.model.shadow", "outcome", "invalid_config").count())
                .as("percent 0 은 꺼짐이지 오류가 아니다 — 나머지 4건만 invalid")
                .isEqualTo(4.0);
    }

    @Test
    @DisplayName("Redis 장애·제출 거부·그림자 호출 실패는 삼켜지고 계측만 남는다")
    void failuresAreSwallowedAndCounted() {
        when(values.get(anyString())).thenThrow(new RuntimeException("connection refused"));
        runner.maybeShadow(primaryRequest());
        assertThat(meters.counter("mio.model.shadow", "outcome", "error").count()).isEqualTo(1.0);

        // 제출 거부 (executor 포화)
        ShadowGenerationRunner rejecting = new ShadowGenerationRunner(
                catalogAllowing(SHADOW_MODEL), redisReturning(SHADOW_MODEL + " 100"), meters,
                llm, preFilter, consent,
                task -> { throw new java.util.concurrent.RejectedExecutionException("full"); });
        rejecting.maybeShadow(primaryRequest());
        assertThat(meters.counter("mio.model.shadow", "outcome", "rejected").count()).isEqualTo(1.0);

        // 그림자 호출 자체가 실패
        RecordingLlmClient failing = new RecordingLlmClient(null);
        ShadowGenerationRunner failingRunner = new ShadowGenerationRunner(
                catalogAllowing(SHADOW_MODEL), redisReturning(SHADOW_MODEL + " 100"), meters,
                failing, preFilter, consent, submitted::add);
        failingRunner.maybeShadow(primaryRequest());
        submitted.forEach(Runnable::run);
        assertThat(meters.counter("mio.model.shadow", "outcome", "shadow_failed").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("사전 필터에 걸린 그림자 응답은 prefilter_failed 로 남는다 — 이것이 shadow 의 존재 이유다")
    void prefilterViolationIsTheSignalWeAreAfter() {
        when(values.get(anyString())).thenReturn(SHADOW_MODEL + " 100");
        when(preFilter.check(anyString()))
                .thenReturn(OutputPreFilterResult.fail(List.of("role_deviation")));

        runner.maybeShadow(primaryRequest());
        submitted.forEach(Runnable::run);

        assertThat(meters.counter("mio.model.shadow", "outcome", "prefilter_failed").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("같은 사용자는 canary 와 같은 버킷 규칙으로 샘플된다 — 팔이 턴마다 튀지 않는다")
    void samplingIsStablePerUser() {
        when(values.get(anyString())).thenReturn(SHADOW_MODEL + " 37");

        for (int i = 0; i < 30; i++) {
            UUID userId = UUID.randomUUID();
            int before = submitted.size();
            runner.maybeShadow(requestFor(userId));
            boolean sampledFirst = submitted.size() > before;
            for (int j = 0; j < 3; j++) {
                int prev = submitted.size();
                runner.maybeShadow(requestFor(userId));
                assertThat(submitted.size() > prev).isEqualTo(sampledFirst);
            }
        }
    }

    // ── 픽스처 ─────────────────────────────────────────────────────

    private static LlmRequest primaryRequest() {
        return requestFor(UUID.randomUUID());
    }

    private static LlmRequest requestFor(UUID userId) {
        return LlmRequest.of("gpt-4o", "시스템 프롬프트", "사용자 발화")
                .withMaxCompletionTokens(400)
                .withAttribution("MAIN_GENERATION", userId, UUID.randomUUID());
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate redisReturning(String value) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(value);
        return template;
    }

    private static ModelCatalog catalogAllowing(String shadowModel) {
        ModelCatalogProperties props = new ModelCatalogProperties();
        List<String> models = new ArrayList<>(java.util.Arrays.stream(ModelRole.values())
                .map(ModelRole::defaultModel).distinct().toList());
        models.add(shadowModel);
        models.add("allowed-but-unpriced");
        props.setAllowed(models);
        LlmPricingProperties pricing = new LlmPricingProperties();
        java.util.Map<String, LlmPricingProperties.ModelPrice> table = new java.util.LinkedHashMap<>();
        for (String model : models) {
            if (!"allowed-but-unpriced".equals(model)) {
                table.put(model, new LlmPricingProperties.ModelPrice(
                        java.math.BigDecimal.ONE, null, java.math.BigDecimal.ONE));
            }
        }
        pricing.setModels(table);
        return new ModelCatalog(props, pricing);
    }

    /** 요청을 붙잡는 가짜 클라이언트. 본문이 null 이면 호출이 실패한다. */
    private static final class RecordingLlmClient implements LlmClient {
        final AtomicReference<LlmRequest> lastRequest = new AtomicReference<>();
        private final String response;

        RecordingLlmClient(String response) {
            this.response = response;
        }

        @Override
        public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
            throw new UnsupportedOperationException("shadow 는 completeText 만 쓴다");
        }

        @Override
        public String completeText(LlmRequest request) {
            lastRequest.set(request);
            if (response == null) {
                throw new IllegalStateException("LLM complete error");
            }
            return response;
        }

        @Override
        public String completeJson(LlmRequest request) {
            throw new UnsupportedOperationException("shadow 는 completeText 만 쓴다");
        }
    }
}
