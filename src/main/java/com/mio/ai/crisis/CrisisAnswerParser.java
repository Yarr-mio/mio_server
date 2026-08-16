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
     *
     * <p>"있"/"없" 은 어간만으로 매칭하지 않는다. 한국어에서 매우 흔한 음절이라
     * "있잖아요"·"상관없이" 같은 무관한 발화까지 확정 답변으로 분류됐다. 서술어 어미가
     * 붙은 형태만 인정해, 답이 아닌 문장은 UNKNOWN 으로 남아 handoff 로 닫히게 한다.
     */
    private static final List<String> YES_MARKERS = List.of(
            "있어", "있습", "있다", "있음", "있네", "있죠", "있지", "있는데",
            "그래", "맞아", "정했", "준비", "구했", "yes");

    /** 한 음절 인정어. 다른 단어의 일부로 흔히 나타나서(반응·언니네) 서두에서만 인정한다. */
    private static final List<String> YES_PREFIXES = List.of("네", "예", "응");

    /** 문장 어디에 있어도 부정으로 보는 마커. 종결형만 인정한다 (YES 쪽 "있"/"없" 과 같은 이유). */
    private static final List<String> NO_MARKERS = List.of(
            "없어", "없습", "없다", "없음", "없네", "없죠", "없지", "없는데",
            "아니요", "아니에요", "아니야", "아닙니다", "아닌데", "no");

    /**
     * 맨 "아니" 는 서두에서만 인정한다. 문장 중간의 "그건 아니고" 류가 확정 NO 가 되는 것을
     * 막는다.
     *
     * <p><b>남은 구멍</b>: 서두의 "아니" 도 "아니 근데"·"아니 진짜" 처럼 부정이 아닌 담화
     * 표지로 매우 흔하다. 여기서 더 좁히면 {@code "아니, 이미 정했어"} 가 UNKNOWN 이 아니라
     * YES 로 확정되는데(준비 완료 진술이 부정 서두를 이김), 그 전이가 옳은지는 임상·제품
     * 판단이라 이 PR 에서 단독으로 뒤집지 않는다 — 이슈 #460 에서 서술형 답변 정책과 함께
     * 정한다.
     */
    private static final List<String> NO_PREFIXES = List.of("아니");

    public CrisisAnswer parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CrisisAnswer.UNKNOWN;
        }

        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^가-힣a-z]", "");
        boolean yes = containsAny(normalized, YES_MARKERS)
                || startsWithAny(normalized, YES_PREFIXES);
        boolean no = containsAny(normalized, NO_MARKERS)
                || startsWithAny(normalized, NO_PREFIXES);

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
