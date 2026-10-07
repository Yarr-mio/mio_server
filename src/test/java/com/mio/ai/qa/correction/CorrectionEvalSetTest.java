package com.mio.ai.qa.correction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("[QA] 정정 시험 세트 로더·검증")
class CorrectionEvalSetTest {

    private static final String VALID = """
            {
              "version": "correction-eval-test",
              "cases": [
                { "id": "C-1", "type": "fact", "strength": "strong", "correction": true,
                  "turns": [ {"role": "USER", "text": "요즘 너무 바빠요"},
                             {"role": "ASSISTANT", "text": "쉬는 게 사치처럼 느껴지시나 봐요"},
                             {"role": "USER", "text": "그게 아니라 시간이 아예 없어요"} ] },
                { "id": "N-1", "type": "control", "correction": false,
                  "turns": [ {"role": "USER", "text": "아니 진짜 너무 힘들어"} ] }
              ]
            }""";

    @Test
    @DisplayName("정정 케이스와 대조군을 읽고 마지막 사용자 발화와 이전 턴을 나눈다")
    void parsesCases() {
        CorrectionEvalSet set = CorrectionEvalSet.fromJson(VALID);

        assertThat(set.version()).isEqualTo("correction-eval-test");
        assertThat(set.cases()).hasSize(2);
        CorrectionEvalCase correction = set.cases().get(0);
        assertThat(correction.userMessage()).isEqualTo("그게 아니라 시간이 아예 없어요");
        assertThat(correction.priorTurns()).hasSize(2);
        assertThat(correction.priorTurns().get(1).isAssistant()).isTrue();
        assertThat(set.cases().get(1).correction()).isFalse();
    }

    @Test
    @DisplayName("마지막 턴이 AI 발화면 거부한다")
    void rejectsLastTurnFromAssistant() {
        String json = VALID.replace(
                "{\"role\": \"USER\", \"text\": \"그게 아니라 시간이 아예 없어요\"}",
                "{\"role\": \"ASSISTANT\", \"text\": \"네\"}");

        assertThatThrownBy(() -> CorrectionEvalSet.fromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("마지막 턴은 사용자 발화");
    }

    @Test
    @DisplayName("정정 케이스에 바로잡을 AI 발화가 없으면 거부한다")
    void rejectsCorrectionWithoutAssistantTurn() {
        String json = """
                { "version": "v", "cases": [
                  { "id": "C-1", "type": "fact", "strength": "weak", "correction": true,
                    "turns": [ {"role": "USER", "text": "그게 아니라 시간이 없어요"} ] } ] }""";

        assertThatThrownBy(() -> CorrectionEvalSet.fromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AI 직전 발화");
    }

    @Test
    @DisplayName("알 수 없는 정정 종류·강도, 정정이 아닌데 control 이 아닌 type 은 거부한다")
    void rejectsUnknownTypeAndStrength() {
        String unknownType = VALID.replace("\"type\": \"fact\"", "\"type\": \"mystery\"");
        String unknownStrength = VALID.replace("\"strength\": \"strong\"", "\"strength\": \"huge\"");
        String controlWithType = VALID.replace("\"type\": \"control\"", "\"type\": \"fact\"");

        assertThatThrownBy(() -> CorrectionEvalSet.fromJson(unknownType))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> CorrectionEvalSet.fromJson(unknownStrength))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("strength");
        assertThatThrownBy(() -> CorrectionEvalSet.fromJson(controlWithType))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("control");
    }

    @Test
    @DisplayName("id 가 중복되면 거부한다")
    void rejectsDuplicateIds() {
        String json = VALID.replace("\"id\": \"N-1\"", "\"id\": \"C-1\"");

        assertThatThrownBy(() -> CorrectionEvalSet.fromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중복");
    }

    @Test
    @DisplayName("adviceRequested 는 선택 필드이고 기본은 거짓이며, 대조군에만 붙일 수 있다")
    void parsesAdviceRequestedLabel() {
        String withLabel = VALID.replace(
                "{ \"id\": \"N-1\", \"type\": \"control\", \"correction\": false,",
                "{ \"id\": \"N-1\", \"type\": \"control\", \"correction\": false, \"adviceRequested\": true,");
        String onCorrection = VALID.replace(
                "\"strength\": \"strong\", \"correction\": true,",
                "\"strength\": \"strong\", \"correction\": true, \"adviceRequested\": true,");
        String nonBoolean = withLabel.replace("\"adviceRequested\": true", "\"adviceRequested\": \"yes\"");

        assertThat(CorrectionEvalSet.fromJson(VALID).cases().get(1).adviceRequested()).isFalse();
        assertThat(CorrectionEvalSet.fromJson(withLabel).cases().get(1).adviceRequested()).isTrue();
        assertThatThrownBy(() -> CorrectionEvalSet.fromJson(onCorrection))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("adviceRequested");
        assertThatThrownBy(() -> CorrectionEvalSet.fromJson(nonBoolean))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("adviceRequested");
    }

    @Test
    @DisplayName("correction 이 불리언이 아니면 거부한다")
    void rejectsNonBooleanCorrection() {
        String json = VALID.replace("\"correction\": false", "\"correction\": \"no\"");

        assertThatThrownBy(() -> CorrectionEvalSet.fromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("correction");
    }
}
