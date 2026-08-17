package com.mio.ai.llm;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 대화 파이프라인에서 LLM 을 부르는 역할 (#479).
 *
 * <p>호출부는 모델 ID 가 아니라 역할을 말하고, 역할→모델 해석은 {@link ModelCatalog} 가 한다.
 * 벤치마크 하네스의 {@code CellModelRole} 과 같은 개념이다 — 평가가 역할 단위로 모델을
 * 갈아끼우며 비교하므로, 운영도 같은 단위로 갈아끼울 수 있어야 평가 결과를 그대로 적용할 수 있다.
 *
 * <p>기본값은 이 enum 이 도입되기 전 각 호출부에 하드코딩돼 있던 상수와 같다.
 * 여기 없는 역할(메모리 컨솔리데이션·리포트·체크인·임베딩)은 아직 호출부 상수를 쓴다 — 이슈 #482.
 */
public enum ModelRole {

    /** 메인 대화 생성 ({@code ConversationOrchestrator}). */
    GENERATION("gpt-4o"),

    /** 입력 안전·보안 판정 ({@code InputJudge}). */
    INPUT_JUDGE("gpt-4o-mini"),

    /** 출력 사후 판정 ({@code OutputJudge}). */
    OUTPUT_JUDGE("gpt-4o-mini"),

    /** CBT 메타데이터 분류 ({@code CbtMetadataClassifier}). */
    CBT_CLASSIFIER("gpt-4o-mini");

    private final String defaultModel;

    ModelRole(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public String defaultModel() {
        return defaultModel;
    }

    /** 설정 파일에서 이 역할을 가리키는 키 (kebab-case). */
    public String configKey() {
        return name().toLowerCase().replace('_', '-');
    }

    /**
     * 설정 키 → 역할. kebab-case 외에 Spring relaxed binding 이 만들 수 있는 표기를 받는다:
     * camelCase, snake_case, 그리고 환경 변수 경로의 점 표기. 두 단어 역할을
     * {@code MIO_AI_MODELS_ROLES_INPUT_JUDGE} 로 넘기면 relaxed binding 이 맵 키를
     * {@code input.judge} 로 만들기 때문에 {@code .} 도 정규화한다 — 운영자가 canary 롤백
     * 중에 bracket 표기를 알아내야 기동하는 상황을 만들지 않는다.
     *
     * @throws IllegalStateException 어느 역할도 아니면 — 오타가 조용히 무시되면
     *                               존재하지 않는 보호를 설정했다고 믿게 된다
     */
    static ModelRole fromConfigKey(String key) {
        String normalized = key.replace("-", "").replace("_", "").replace(".", "").toLowerCase();
        return Arrays.stream(values())
                .filter(role -> role.name().replace("_", "").toLowerCase().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "mio.ai.models.roles 에 모르는 역할 키가 있다: '%s' — 가능한 키: %s"
                                .formatted(key, Arrays.stream(values())
                                        .map(ModelRole::configKey)
                                        .collect(Collectors.joining(", ")))));
    }
}
