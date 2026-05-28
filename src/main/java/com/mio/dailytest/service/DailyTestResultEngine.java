package com.mio.dailytest.service;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.dailytest.dto.DailyTestContent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class DailyTestResultEngine {

    private static final int THRESHOLD_LOW = 4;
    private static final int THRESHOLD_HIGH = 8;

    public record TestResult(String summary, String description, List<String> tags, String characterComment) {}

    public TestResult calculate(DailyTestContent content, Map<String, String> answers) {
        if (content == null || content.questions() == null || content.questions().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_DAILY_TEST_CONTENT);
        }
        if (answers == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        int totalScore = content.questions().stream()
                .mapToInt(q -> {
                    String selectedOptionId = answers.get(q.id());
                    if (selectedOptionId == null || q.options() == null) return 0;
                    return q.options().stream()
                            .filter(o -> o.id().equals(selectedOptionId))
                            .mapToInt(DailyTestContent.Option::score)
                            .findFirst()
                            .orElse(0);
                })
                .sum();

        List<String> tags = content.questions().stream()
                .flatMap(q -> {
                    String selectedOptionId = answers.get(q.id());
                    if (selectedOptionId == null || q.options() == null) return Stream.empty();
                    return q.options().stream()
                            .filter(o -> o.id().equals(selectedOptionId))
                            .flatMap(o -> o.tags() != null ? o.tags().stream().filter(t -> t != null) : Stream.empty());
                })
                .distinct()
                .toList();

        String summary, description, characterComment;
        if (totalScore < THRESHOLD_LOW) {
            summary = "오늘은 비교적 안정된 하루였네요.";
            description = "감정이 안정적으로 유지된 하루예요. 작은 스트레스도 잘 다루고 있어요.";
            characterComment = "미오가 말해요: 차분하게 하루를 보낸 네가 대단해 🐧";
        } else if (totalScore < THRESHOLD_HIGH) {
            summary = "감정의 기복이 있었던 하루군요.";
            description = "다소 감정의 변화가 있었던 하루예요. 잠시 숨을 고르는 시간을 가져보세요.";
            characterComment = "미오가 말해요: 기복이 있어도 잘 헤쳐나가고 있어 🐧";
        } else {
            summary = "힘든 하루를 보냈군요. 충분히 쉬어요.";
            description = "오늘은 많이 지쳤을 거예요. 자신을 위한 시간을 충분히 가져보세요.";
            characterComment = "미오가 말해요: 힘들었겠지만 여기까지 온 것만으로도 충분해 🐧";
        }
        return new TestResult(summary, description, tags, characterComment);
    }
}
