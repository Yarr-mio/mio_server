package com.mio.dailytest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * status=pending: testId, title, description, questions 포함
 * status=completed: testId, resultSummary 포함, questions null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DailyTestTodayResponse(
        String status,
        UUID testId,
        String title,
        String description,
        List<QuestionDto> questions,
        String resultSummary
) {
    public record QuestionDto(String id, int order, String text, List<OptionDto> options) {}

    public record OptionDto(String id, String text) {}

    public static DailyTestTodayResponse pending(UUID testId, String title, String description,
                                                  List<QuestionDto> questions) {
        return new DailyTestTodayResponse("pending", testId, title, description, questions, null);
    }

    public static DailyTestTodayResponse completed(UUID testId, String resultSummary) {
        return new DailyTestTodayResponse("completed", testId, null, null, null, resultSummary);
    }
}
