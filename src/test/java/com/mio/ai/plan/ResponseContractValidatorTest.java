package com.mio.ai.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 이슈 #303 — 결정론적 계약 검사. */
class ResponseContractValidatorTest {

    private final ResponseContractValidator validator = new ResponseContractValidator();

    private ResponsePlan plan(int maxQuestions, int maxSentences, String... forbidden) {
        List<String> elements = forbidden.length > 0 ? List.of(forbidden) : ResponsePlan.BASE_FORBIDDEN;
        return new ResponsePlan(ResponseAct.EMOTION_CHECK, GenerationFreedom.CONSTRAINED,
                maxQuestions, maxSentences, elements);
    }

    @Test
    @DisplayName("질문 수 상한을 넘으면 위반이다")
    void tooManyQuestionsViolatesContract() {
        ResponseContractResult result = validator.validate(plan(1, 4),
                "많이 힘드셨겠어요. 언제부터 그랬나요? 어떤 상황이었어요?");

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.startsWith("max_questions"));
    }

    @Test
    @DisplayName("문장 수 상한을 넘으면 위반이다")
    void tooManySentencesViolatesContract() {
        ResponseContractResult result = validator.validate(plan(1, 2),
                "그랬군요. 많이 힘드셨겠어요. 저도 마음이 아프네요. 어떤 기분이 드나요?");

        assertThat(result.violations()).anyMatch(v -> v.startsWith("max_sentences"));
    }

    @Test
    @DisplayName("상한 안의 응답은 통과한다")
    void compliantResponsePasses() {
        ResponseContractResult result = validator.validate(plan(1, 4),
                "많이 지치셨겠어요. 지금 어떤 기분이 가장 크게 느껴지나요?");

        assertThat(result.applicable()).isTrue();
        assertThat(result.passed()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    @DisplayName("결과 보장 표현을 잡는다")
    void guaranteedOutcomeIsViolation() {
        ResponseContractResult result = validator.validate(plan(1, 4),
                "괜찮아요. 반드시 좋아질 거예요.");

        assertThat(result.violations()).contains("guaranteed_outcome");
    }

    @Test
    @DisplayName("사용자를 단정하는 표현을 잡는다")
    void certaintyAboutUserIsViolation() {
        ResponseContractResult result = validator.validate(plan(1, 4),
                "당신은 완벽주의 성격이에요.");

        assertThat(result.violations()).contains("certainty_about_user");
    }

    @Test
    @DisplayName("HIGH 계획에서 금지한 조언·CBT 개입을 잡는다")
    void forbiddenElementsAreDetected() {
        ResponsePlan highRiskPlan = plan(1, 3,
                "certainty_about_user", "guaranteed_outcome", "advice", "cbt_intervention");

        assertThat(validator.validate(highRiskPlan, "산책을 해보세요.").violations())
                .contains("advice");
        assertThat(validator.validate(highRiskPlan, "그 생각의 근거를 따져볼까요?").violations())
                .contains("cbt_intervention");
    }

    @Test
    @DisplayName("계획되지 않은 턴은 통과가 아니라 검사 대상 아님이다")
    void unplannedTurnIsNotApplicable() {
        ResponseContractResult result = validator.validate(
                ResponsePlan.unplanned(), "질문이 여러 개예요? 이건요? 저건요?");

        assertThat(result.applicable())
                .as("계획 밖 턴을 준수로 세면 준수율이 실제보다 높아 보인다")
                .isFalse();
        assertThat(result.logValue()).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    @DisplayName("서버 고정 문구는 계약 검사 대상이 아니다")
    void templateOnlyPlanIsNotApplicable() {
        ResponseContractResult result = validator.validate(
                ResponsePlan.fixed(ResponseAct.RESOURCE_HANDOFF), "도움받을 수 있는 곳을 안내할게요.");

        assertThat(result.applicable()).isFalse();
    }
}
