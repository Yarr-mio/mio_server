package com.mio.dailytest.service;

import com.mio.dailytest.dto.DailyTestContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DailyTestResultEngineTest {

    private final DailyTestResultEngine engine = new DailyTestResultEngine();

    private static final DailyTestContent CONTENT = new DailyTestContent(List.of(
            new DailyTestContent.Question("q1", 1, "질문1", List.of(
                    new DailyTestContent.Option("q1_a", "낮음", 0, List.of("neutral")),
                    new DailyTestContent.Option("q1_b", "중간", 2, List.of("moderate")),
                    new DailyTestContent.Option("q1_c", "높음", 4, List.of("high"))
            )),
            new DailyTestContent.Question("q2", 2, "질문2", List.of(
                    new DailyTestContent.Option("q2_a", "낮음", 0, List.of("neutral")),
                    new DailyTestContent.Option("q2_b", "중간", 3, List.of("moderate")),
                    new DailyTestContent.Option("q2_c", "높음", 5, List.of("high"))
            ))
    ));

    @Test
    @DisplayName("총점 0~3 → 안정 메시지 반환")
    void calculate_lowScore_returnsStableMessage() {
        Map<String, String> answers = Map.of("q1", "q1_a", "q2", "q2_a"); // 0+0=0
        DailyTestResultEngine.TestResult result = engine.calculate(CONTENT, answers);
        assertThat(result.summary()).isEqualTo("오늘은 비교적 안정된 하루였네요.");
        assertThat(result.description()).isEqualTo("감정이 안정적으로 유지된 하루예요. 작은 스트레스도 잘 다루고 있어요.");
        assertThat(result.tags()).containsExactlyInAnyOrder("neutral");
        assertThat(result.characterComment()).isEqualTo("미오가 말해요: 차분하게 하루를 보낸 네가 대단해 🐧");
    }

    @Test
    @DisplayName("총점 4~7 → 중간 메시지 반환")
    void calculate_moderateScore_returnsModerateMessage() {
        Map<String, String> answers = Map.of("q1", "q1_b", "q2", "q2_b"); // 2+3=5
        DailyTestResultEngine.TestResult result = engine.calculate(CONTENT, answers);
        assertThat(result.summary()).isEqualTo("감정의 기복이 있었던 하루군요.");
        assertThat(result.description()).isEqualTo("다소 감정의 변화가 있었던 하루예요. 잠시 숨을 고르는 시간을 가져보세요.");
        assertThat(result.tags()).containsExactlyInAnyOrder("moderate");
        assertThat(result.characterComment()).isEqualTo("미오가 말해요: 기복이 있어도 잘 헤쳐나가고 있어 🐧");
    }

    @Test
    @DisplayName("총점 8 이상 → 힘든 하루 메시지 반환")
    void calculate_highScore_returnsHardMessage() {
        Map<String, String> answers = Map.of("q1", "q1_c", "q2", "q2_c"); // 4+5=9
        DailyTestResultEngine.TestResult result = engine.calculate(CONTENT, answers);
        assertThat(result.summary()).isEqualTo("힘든 하루를 보냈군요. 충분히 쉬어요.");
        assertThat(result.description()).isEqualTo("오늘은 많이 지쳤을 거예요. 자신을 위한 시간을 충분히 가져보세요.");
        assertThat(result.tags()).containsExactlyInAnyOrder("high");
        assertThat(result.characterComment()).isEqualTo("미오가 말해요: 힘들었겠지만 여기까지 온 것만으로도 충분해 🐧");
    }

    @Test
    @DisplayName("답변 없는 문항은 0점 처리")
    void calculate_missingAnswer_treatedAsZero() {
        Map<String, String> answers = Map.of("q1", "q1_a"); // q2 없음 → 0+0=0
        DailyTestResultEngine.TestResult result = engine.calculate(CONTENT, answers);
        assertThat(result.summary()).isEqualTo("오늘은 비교적 안정된 하루였네요.");
        assertThat(result.tags()).containsExactlyInAnyOrder("neutral");
    }

    @Test
    @DisplayName("존재하지 않는 optionId는 0점 처리")
    void calculate_unknownOptionId_treatedAsZero() {
        Map<String, String> answers = Map.of("q1", "q1_z", "q2", "q2_a"); // 0+0=0
        DailyTestResultEngine.TestResult result = engine.calculate(CONTENT, answers);
        assertThat(result.summary()).isEqualTo("오늘은 비교적 안정된 하루였네요.");
        assertThat(result.tags()).containsExactlyInAnyOrder("neutral");
    }

    @Test
    @DisplayName("경계값 3점 → 안정 (stable 상한)")
    void calculate_boundary3_returnsStableMessage() {
        Map<String, String> answers = Map.of("q1", "q1_a", "q2", "q2_b"); // 0+3=3
        DailyTestResultEngine.TestResult result = engine.calculate(CONTENT, answers);
        assertThat(result.summary()).isEqualTo("오늘은 비교적 안정된 하루였네요.");
        assertThat(result.tags()).containsExactlyInAnyOrder("neutral", "moderate");
    }

    @Test
    @DisplayName("경계값 4점 → 중간 (moderate 하한)")
    void calculate_boundary4_returnsModerateMessage() {
        Map<String, String> answers = Map.of("q1", "q1_c", "q2", "q2_a"); // 4+0=4
        DailyTestResultEngine.TestResult result = engine.calculate(CONTENT, answers);
        assertThat(result.summary()).isEqualTo("감정의 기복이 있었던 하루군요.");
        assertThat(result.tags()).containsExactlyInAnyOrder("high", "neutral");
    }

    @Test
    @DisplayName("경계값 7점 → 중간 (moderate 상한)")
    void calculate_boundary7_returnsModerateMessage() {
        Map<String, String> answers = Map.of("q1", "q1_c", "q2", "q2_b"); // 4+3=7
        DailyTestResultEngine.TestResult result = engine.calculate(CONTENT, answers);
        assertThat(result.summary()).isEqualTo("감정의 기복이 있었던 하루군요.");
        assertThat(result.tags()).containsExactlyInAnyOrder("high", "moderate");
    }

    @Test
    @DisplayName("경계값 8점 → 힘든 하루 (hard 하한)")
    void calculate_boundary8_returnsHardMessage() {
        DailyTestContent thresholdContent = new DailyTestContent(List.of(
                new DailyTestContent.Question("q1", 1, "질문1", List.of(
                        new DailyTestContent.Option("q1_hard", "매우 높음", 8, List.of("high"))
                ))
        ));
        Map<String, String> answers = Map.of("q1", "q1_hard");
        DailyTestResultEngine.TestResult result = engine.calculate(thresholdContent, answers);
        assertThat(result.summary()).isEqualTo("힘든 하루를 보냈군요. 충분히 쉬어요.");
        assertThat(result.tags()).containsExactlyInAnyOrder("high");
    }
}
