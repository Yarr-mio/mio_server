package com.mio.ai.crisis;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 예·아니오 고정 질문의 명시적 답만 구조화한다. 자유 서술을 위험 판단으로 추론하지 않는다.
 *
 * <p>마커는 문장 위치와 무관하게 양방향 모두 탐지한다. 서두만 읽으면 "네, 없어요"가 YES 로,
 * "아니, 이미 정했어"가 NO 로 확정된다 — 후자는 준비 완료 진술을 지우는 방향의 오류다.
 * 긍정·부정 마커가 함께 있으면 확정하지 않고 UNKNOWN 을 반환하며, 상태기계가 UNKNOWN 을
 * 고정 handoff 로 fail-closed 처리한다.
 */
@Component
public class CrisisAnswerParser {

    /**
     * 문장 어디에 있어도 긍정으로 보는 마커. 정했·준비·구했 같은 준비 행동 동사는
     * 부정 서두 뒤에 나와도 긍정 증거다.
     */
    private static final List<String> YES_MARKERS = List.of(
            "그래", "맞아", "있", "정했", "준비", "구했", "yes");

    /** 한 음절 인정어. 다른 단어의 일부로 흔히 나타나서(반응·언니네) 서두에서만 인정한다. */
    private static final List<String> YES_PREFIXES = List.of("네", "예", "응");

    private static final List<String> NO_MARKERS = List.of("아니", "없", "no");

    public CrisisAnswer parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CrisisAnswer.UNKNOWN;
        }

        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^가-힣a-z]", "");
        boolean yes = containsAny(normalized, YES_MARKERS)
                || startsWithAny(normalized, YES_PREFIXES);
        boolean no = containsAny(normalized, NO_MARKERS);

        if (yes == no) {
            return CrisisAnswer.UNKNOWN;
        }
        return yes ? CrisisAnswer.YES : CrisisAnswer.NO;
    }

    private boolean containsAny(String value, List<String> markers) {
        for (String marker : markers) {
            if (value.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWithAny(String value, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
