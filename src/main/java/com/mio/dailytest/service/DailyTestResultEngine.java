package com.mio.dailytest.service;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.dailytest.dto.DailyTestContent;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DailyTestResultEngine {

    private static final int THRESHOLD_LOW = 4;
    private static final int THRESHOLD_HIGH = 8;

    private static final String RESULT_STABLE = "오늘은 비교적 안정된 하루였네요.";
    private static final String RESULT_MODERATE = "감정의 기복이 있었던 하루군요.";
    private static final String RESULT_HARD = "힘든 하루를 보냈군요. 충분히 쉬어요.";

    /**
     * @param content 테스트 문항 구조 (JSONB 역직렬화 결과)
     * @param answers questionId → optionId 매핑
     * @return result summary 문자열
     */
    public String calculate(DailyTestContent content, Map<String, String> answers) {
        if (content == null || content.questions() == null || content.questions().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_DAILY_TEST_CONTENT);
        }
        int totalScore = content.questions().stream()
                .mapToInt(question -> {
                    String selectedOptionId = answers.get(question.id());
                    if (selectedOptionId == null) return 0;
                    return question.options().stream()
                            .filter(opt -> opt.id().equals(selectedOptionId))
                            .mapToInt(DailyTestContent.Option::score)
                            .findFirst()
                            .orElse(0);
                })
                .sum();

        if (totalScore < THRESHOLD_LOW) return RESULT_STABLE;
        if (totalScore < THRESHOLD_HIGH) return RESULT_MODERATE;
        return RESULT_HARD;
    }
}
