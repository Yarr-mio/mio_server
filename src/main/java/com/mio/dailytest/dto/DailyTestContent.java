package com.mio.dailytest.dto;

import java.util.List;

public record DailyTestContent(List<Question> questions) {

    public DailyTestContent {
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("questions must not be null or empty");
        }
    }

    public record Question(String id, int order, String text, List<Option> options) {

        public Question {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Question id must not be null or blank");
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Question text must not be null or blank");
            }
            if (options == null || options.isEmpty()) {
                throw new IllegalArgumentException("Question options must not be null or empty");
            }
        }
    }

    public record Option(String id, String text, int score, List<String> tags) {

        public Option {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Option id must not be null or blank");
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Option text must not be null or blank");
            }
        }
    }
}
