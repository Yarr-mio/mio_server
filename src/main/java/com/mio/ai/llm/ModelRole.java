package com.mio.ai.llm;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LLM 을 부르는 역할 (#479, 전 호출부 편입 #482).
 *
 * <p>호출부는 모델 ID 가 아니라 역할을 말하고, 역할→모델 해석은 {@link ModelCatalog} 가 한다.
 * 벤치마크 하네스의 {@code CellModelRole} 과 같은 개념이다 — 평가가 역할 단위로 모델을
 * 갈아끼우며 비교하므로, 운영도 같은 단위로 갈아끼울 수 있어야 평가 결과를 그대로 적용할 수 있다.
 *
 * <p>기본값은 이 enum 이 도입되기 전 각 호출부에 하드코딩돼 있던 상수와 같다.
 * 프로덕션 소스에 모델 리터럴이 이 파일 밖에 남지 않는 것을 테스트가 감시한다.
 */
public enum ModelRole {

    /** 메인 대화 생성 ({@code ConversationOrchestrator}). */
    GENERATION("gpt-4o"),

    /** 입력 안전·보안 판정 ({@code InputJudge}). */
    INPUT_JUDGE("gpt-4o-mini"),

    /** 출력 사후 판정 ({@code OutputJudge}). */
    OUTPUT_JUDGE("gpt-4o-mini"),

    /** CBT 메타데이터 분류 ({@code CbtMetadataClassifier}). */
    CBT_CLASSIFIER("gpt-4o-mini"),

    /** 턴 온톨로지 추출 ({@code LlmTurnOntologyExtractor}). */
    ONTOLOGY_EXTRACTOR("gpt-4o-mini"),

    /** 세션 내부 요약 ({@code SessionConsolidator}). */
    SESSION_SUMMARY("gpt-4o-mini"),

    /** 사용자 노출용 요약 렌더링 ({@code SessionSummaryRenderer}). */
    SUMMARY_RENDERER("gpt-4o-mini"),

    /** 대화 체크포인트 ({@code ConversationCheckpointService}). */
    CHECKPOINT("gpt-4o-mini"),

    /** Todo 행동 개인화 ({@code TodoActionPersonalizer}). */
    TODO_PERSONALIZER("gpt-4o-mini"),

    /** 에피소드 추출 ({@code ExtractorLlmClient}). */
    EPISODE_EXTRACTOR("gpt-4o-mini"),

    /** 주간 리플렉션 ({@code WeeklyReflectionJob}). */
    WEEKLY_REFLECTION("gpt-4o-mini"),

    /** 리포트 서사 ({@code ReportNarrativeService}). */
    REPORT_NARRATIVE("gpt-4o-mini"),

    /** 체크인 응답 ({@code CheckinAiResponseGenerator}). */
    CHECKIN_RESPONSE("gpt-4o-mini"),

    /** 임베딩 ({@code OpenAiLlmClient}). */
    EMBEDDING("text-embedding-3-small");

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
     * 설정 키 → 역할. 받는 표기는 <b>단어 경계가 맞는 것만</b>이다: kebab({@code input-judge}),
     * snake({@code input_judge}), 환경 변수 relaxed binding 의 점 표기({@code input.judge} —
     * {@code MIO_AI_MODELS_ROLES_INPUT_JUDGE} 가 이렇게 바인딩된다), camelCase({@code inputJudge}).
     *
     * <p>구분자를 위치와 무관하게 지우는 정규화는 쓰지 않는다 — {@code gener.ation} 같은
     * 기형 키가 해석되면 오타가 존재하지 않는 보호를 가장한다 (#483 리뷰 이월 지적).
     *
     * @throws IllegalStateException 어느 역할의 정당한 표기도 아니면
     */
    static ModelRole fromConfigKey(String key) {
        String lowered = key.toLowerCase();
        return Arrays.stream(values())
                .filter(role -> role.acceptedKeys().contains(lowered))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "mio.ai.models.roles 에 모르는 역할 키가 있다: '%s' — 가능한 키: %s"
                                .formatted(key, Arrays.stream(values())
                                        .map(ModelRole::configKey)
                                        .collect(Collectors.joining(", ")))));
    }

    /** 이 역할의 정당한 소문자 표기들. 구분자가 단어 경계 위치에만 있는 형태로 한정한다. */
    private Set<String> acceptedKeys() {
        String snake = name().toLowerCase();
        return new HashSet<>(List.of(
                snake,                       // input_judge
                snake.replace('_', '-'),     // input-judge (yml kebab)
                snake.replace('_', '.'),     // input.judge (환경 변수 relaxed binding)
                snake.replace("_", "")));    // inputjudge (camelCase 소문자화)
    }
}
