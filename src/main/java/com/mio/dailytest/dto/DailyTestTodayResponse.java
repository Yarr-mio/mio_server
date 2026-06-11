package com.mio.dailytest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DailyTestTodayResponse(
        boolean completedToday,
        UUID testId,
        String title,
        Integer estimatedMinutes,
        List<QuestionDto> questions,
        ResultDto result
) {
    public record ResultDto(String summary, List<String> tags) {}

    public record QuestionDto(
            String questionId,
            int order,
            String text,
            List<OptionDto> options
    ) {}

    public record OptionDto(
            String optionId,
            String text
    ) {}

    public static DailyTestTodayResponse pending(UUID testId, String title, int estimatedMinutes,
                                                  List<QuestionDto> questions) {
        return new DailyTestTodayResponse(false, testId, title, estimatedMinutes, questions, null);
    }

    public static DailyTestTodayResponse completed(ResultDto result) {
        return new DailyTestTodayResponse(true, null, null, null, null, result);
    }
}
