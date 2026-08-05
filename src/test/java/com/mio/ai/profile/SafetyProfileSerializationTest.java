package com.mio.ai.profile;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SafetyProfile 은 Redis 에 JSON 으로 캐싱된다 (TTL 90분).
 *
 * <p>필드를 추가한 배포 직후에는 이전 형태로 직렬화된 항목이 캐시에 남아 있다.
 * 그 JSON 이 역직렬화에 실패하면 프로파일 로드가 통째로 실패하므로 고정해 둔다 (이슈 #261).
 */
class SafetyProfileSerializationTest {

    /** 알 수 없는 필드에 엄격한 매퍼 — 직렬화 형태가 그대로 되읽히는지 검증한다. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 프로덕션(Spring Boot 자동 구성)과 같이 알 수 없는 필드를 무시하는 매퍼. */
    private final ObjectMapper lenientMapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    @DisplayName("degraded 필드가 없는 이전 형태의 캐시 JSON도 역직렬화된다")
    void deserializesLegacyJsonWithoutDegradedField() throws Exception {
        String legacyJson = """
                {"userId":"u1","source":"personalized",
                 "dynamicThresholds":{"emotion_drop_threshold":25.0},
                 "effectiveInterventions":[],"ineffectiveInterventions":[],
                 "policyFlags":["force_judge"],"riskPriorScore":0.9,
                 "recentCrisisSeverityMax":3,"commonDistortionCodes":[],
                 "activeNegativeBeliefCount":0,"copingStyle":null,
                 "dominantTriggerKinds":[],"sensitivityCap":"sensitive"}
                """;

        SafetyProfile profile = objectMapper.readValue(legacyJson, SafetyProfile.class);

        assertThat(profile.recentCrisisSeverityMax()).isEqualTo(3);
        assertThat(profile.hasForceJudge()).isTrue();
        assertThat(profile.degraded())
                .as("필드가 없던 시절의 프로파일은 정상 조회로 만들어진 것이다")
                .isFalse();
    }

    @Test
    @DisplayName("degraded 프로파일은 직렬화 왕복 후에도 값이 유지된다")
    void degradedSurvivesRoundTrip() throws Exception {
        SafetyProfile degraded = new SafetyProfile(
                "u1", SafetyProfile.SOURCE_DEFAULT,
                Map.of("emotion_drop_threshold", 25.0),
                List.of(), List.of(), List.of("force_judge"),
                0.0, 0, List.of(),
                0, null, List.of(), "sensitive",
                true);

        String json = objectMapper.writeValueAsString(degraded);
        SafetyProfile restored = objectMapper.readValue(json, SafetyProfile.class);

        assertThat(restored.degraded())
                .as("캐시를 거쳐도 근거 없이 만들어진 프로파일임을 잃지 않는다")
                .isTrue();
        assertThat(restored.hasForceJudge()).isTrue();

        assertThat(json)
                .as("isPersonalized() 는 파생값이라 직렬화 형태에 섞이면 안 된다")
                .doesNotContain("\"personalized\"");
    }

    /**
     * {@code isPersonalized()} 가 게터로 인식되던 시절의 캐시 항목에는 {@code "personalized"} 가 들어 있다.
     * 배포 직후 그 항목들이 남아 있으므로 관대한 매퍼에서 계속 읽혀야 한다.
     */
    @Test
    @DisplayName("파생 필드가 섞여 있던 이전 캐시 항목도 계속 읽힌다")
    void deserializesLegacyJsonWithDerivedPersonalizedField() throws Exception {
        String legacyJson = """
                {"userId":"u1","source":"personalized","personalized":true,
                 "dynamicThresholds":{},
                 "effectiveInterventions":[],"ineffectiveInterventions":[],
                 "policyFlags":[],"riskPriorScore":0.0,
                 "recentCrisisSeverityMax":0,"commonDistortionCodes":[],
                 "activeNegativeBeliefCount":0,"copingStyle":null,
                 "dominantTriggerKinds":[],"sensitivityCap":"sensitive"}
                """;

        SafetyProfile profile = lenientMapper.readValue(legacyJson, SafetyProfile.class);

        assertThat(profile.isPersonalized()).isTrue();
        assertThat(profile.degraded()).isFalse();
    }
}
