package com.mio.ai.llm;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 생성 모델 canary 라우팅 (#480).
 *
 * <p>이 라우터의 안전 성질은 하나다 — <b>확신이 없으면 기본 모델이다.</b> 설정 없음, 파싱 실패,
 * allowlist 밖, 단가 미등록, Redis 장애 전부 기본 팔로 떨어진다. canary 는 검증된 후보를
 * 일부 트래픽에 태우는 장치이지, 장애 시 미검증 모델로 새는 통로가 아니다.
 */
class GenerationCanaryRouterTest {

    private static final String CANDIDATE = "gpt-4.1-nano";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SimpleMeterRegistry meters;
    private GenerationCanaryRouter router;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        meters = new SimpleMeterRegistry();
        router = new GenerationCanaryRouter(catalogAllowing(CANDIDATE), redis, meters);
    }

    @Test
    @DisplayName("설정이 없으면 카탈로그 기본 모델이다")
    void noConfigMeansDefault() {
        when(values.get(anyString())).thenReturn(null);

        assertThat(router.modelFor(UUID.randomUUID()))
                .isEqualTo(ModelRole.GENERATION.defaultModel());
    }

    @Test
    @DisplayName("percent 100 이면 모든 사용자가 후보 팔이다")
    void fullPercentRoutesEveryoneToCandidate() {
        when(values.get(anyString())).thenReturn(CANDIDATE + " 100");

        for (int i = 0; i < 20; i++) {
            assertThat(router.modelFor(UUID.randomUUID())).isEqualTo(CANDIDATE);
        }
    }

    @Test
    @DisplayName("percent 0 은 즉시 롤백이다 — 새 턴부터 전원 기본 팔")
    void zeroPercentIsTheRollbackPath() {
        when(values.get(anyString())).thenReturn(CANDIDATE + " 0");

        for (int i = 0; i < 20; i++) {
            assertThat(router.modelFor(UUID.randomUUID()))
                    .isEqualTo(ModelRole.GENERATION.defaultModel());
        }
    }

    @Test
    @DisplayName("같은 사용자는 항상 같은 팔이다 — 세션이 바뀌어도")
    void sameUserAlwaysSameArm() {
        when(values.get(anyString())).thenReturn(CANDIDATE + " 37");

        for (int i = 0; i < 50; i++) {
            UUID userId = UUID.randomUUID();
            String first = router.modelFor(userId);
            for (int j = 0; j < 5; j++) {
                assertThat(router.modelFor(userId)).isEqualTo(first);
            }
        }
    }

    @Test
    @DisplayName("percent 를 올리면 후보 팔 사용자는 후보 팔에 남는다 — 버킷이 단조라 팔이 튀지 않는다")
    void raisingPercentKeepsExistingCandidateUsers() {
        List<UUID> users = java.util.stream.Stream.generate(UUID::randomUUID)
                .limit(200).toList();

        when(values.get(anyString())).thenReturn(CANDIDATE + " 10");
        List<UUID> onCandidateAt10 = users.stream()
                .filter(u -> CANDIDATE.equals(router.modelFor(u)))
                .toList();

        when(values.get(anyString())).thenReturn(CANDIDATE + " 40");
        for (UUID user : onCandidateAt10) {
            assertThat(router.modelFor(user))
                    .as("10%% 에서 후보였던 사용자가 40%% 로 올리자 기본 팔로 튀면 대화 연속성이 깨진다")
                    .isEqualTo(CANDIDATE);
        }
    }

    @Test
    @DisplayName("allowlist 밖 후보는 무시된다 — canary 가 안전 게이트를 우회하는 통로가 되면 안 된다")
    void candidateOutsideAllowlistIsIgnored() {
        when(values.get(anyString())).thenReturn("model-nobody-approved 100");

        assertThat(router.modelFor(UUID.randomUUID()))
                .isEqualTo(ModelRole.GENERATION.defaultModel());
        assertThat(meters.counter("mio.model.canary", "outcome", "invalid_config").count())
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("단가 미등록 후보는 무시된다 — 비용 '미상' 트래픽을 canary 가 만들면 안 된다")
    void unpricedCandidateIsIgnored() {
        router = new GenerationCanaryRouter(
                catalogAllowingButUnpriced("allowed-but-unpriced"), redis, meters);
        when(values.get(anyString())).thenReturn("allowed-but-unpriced 100");

        assertThat(router.modelFor(UUID.randomUUID()))
                .isEqualTo(ModelRole.GENERATION.defaultModel());
        assertThat(meters.counter("mio.model.canary", "outcome", "invalid_config").count())
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("깨진 설정 값은 기본 팔 + 계측이다 — 조용히 무시되지도, 턴을 죽이지도 않는다")
    void malformedConfigFallsBackAndCounts() {
        for (String broken : new String[]{"gpt-4.1-nano", "gpt-4.1-nano abc",
                "gpt-4.1-nano 101", "gpt-4.1-nano -1", "a b c d"}) {
            when(values.get(anyString())).thenReturn(broken);
            assertThat(router.modelFor(UUID.randomUUID()))
                    .as("깨진 설정 '%s' 는 기본 팔이어야 한다", broken)
                    .isEqualTo(ModelRole.GENERATION.defaultModel());
        }
        assertThat(meters.counter("mio.model.canary", "outcome", "invalid_config").count())
                .isGreaterThanOrEqualTo(5);

        // 공백 값은 깨진 설정이 아니라 설정 부재다 — 기본 팔로 가되 invalid 로 세지 않는다.
        when(values.get(anyString())).thenReturn("   ");
        assertThat(router.modelFor(UUID.randomUUID()))
                .isEqualTo(ModelRole.GENERATION.defaultModel());
        assertThat(meters.counter("mio.model.canary", "outcome", "invalid_config").count())
                .isEqualTo(5.0);
    }

    @Test
    @DisplayName("Redis 장애 시 기본 팔이다 — 라우팅을 '모른다'가 미검증 모델이 되면 안 된다")
    void redisFailureFallsBackToDefault() {
        when(values.get(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertThat(router.modelFor(UUID.randomUUID()))
                .isEqualTo(ModelRole.GENERATION.defaultModel());
        assertThat(meters.counter("mio.model.canary", "outcome", "error").count())
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("팔 배정이 계측된다 — 배포 전후 비교의 분모가 여기서 나온다")
    void armAssignmentIsCounted() {
        when(values.get(anyString())).thenReturn(CANDIDATE + " 100");
        router.modelFor(UUID.randomUUID());
        when(values.get(anyString())).thenReturn(null);
        router.modelFor(UUID.randomUUID());

        assertThat(meters.counter("mio.model.canary", "outcome", "candidate").count())
                .isEqualTo(1.0);
        assertThat(meters.counter("mio.model.canary", "outcome", "default").count())
                .isEqualTo(1.0);
    }

    // ── 픽스처 ─────────────────────────────────────────────────────

    private static ModelCatalog catalogAllowing(String candidate) {
        return catalog(List.of("gpt-4o", "gpt-4o-mini", candidate),
                List.of("gpt-4o", "gpt-4o-mini", candidate));
    }

    private static ModelCatalog catalogAllowingButUnpriced(String candidate) {
        return catalog(List.of("gpt-4o", "gpt-4o-mini", candidate),
                List.of("gpt-4o", "gpt-4o-mini"));
    }

    private static ModelCatalog catalog(List<String> allowed, List<String> priced) {
        ModelCatalogProperties props = new ModelCatalogProperties();
        List<String> withEmbedding = new java.util.ArrayList<>(allowed);
        withEmbedding.add(ModelRole.EMBEDDING.defaultModel());
        props.setAllowed(withEmbedding);
        LlmPricingProperties pricing = new LlmPricingProperties();
        Map<String, LlmPricingProperties.ModelPrice> table = new LinkedHashMap<>();
        for (String model : priced) {
            table.put(model, new LlmPricingProperties.ModelPrice(
                    BigDecimal.ONE, null, BigDecimal.ONE));
        }
        table.putIfAbsent(ModelRole.EMBEDDING.defaultModel(),
                new LlmPricingProperties.ModelPrice(BigDecimal.ONE, null, BigDecimal.ONE));
        pricing.setModels(table);
        return new ModelCatalog(props, pricing);
    }
}
