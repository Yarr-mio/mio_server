package com.mio.dailytest.dto;

import java.util.List;

public record DailyTestContent(List<Question> questions) {

    public record Question(String id, int order, String text, List<Option> options) {}

    public record Option(String id, String text, int score, List<String> tags) {}
}
