package com.mio.ai.crisis;

import org.springframework.stereotype.Component;

import java.util.Locale;

/** 예·아니오 고정 질문의 명시적 답만 구조화한다. 자유 서술을 위험 판단으로 추론하지 않는다. */
@Component
public class CrisisAnswerParser {

    public CrisisAnswer parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CrisisAnswer.UNKNOWN;
        }

        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^가-힣a-z]", "");
        boolean yes = startsWithAny(normalized, "네", "예", "응", "그래", "맞아", "yes")
                || normalized.contains("있어");
        boolean no = startsWithAny(normalized, "아니", "없어", "없다", "no");

        if (yes == no) {
            return CrisisAnswer.UNKNOWN;
        }
        return yes ? CrisisAnswer.YES : CrisisAnswer.NO;
    }

    private boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
