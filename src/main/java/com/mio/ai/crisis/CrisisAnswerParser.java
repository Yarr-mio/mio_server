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
 *
 * <p>마커 스캔보다 먼저 보는 것이 하나 있다 — 부정 보조용언 {@code -지 않다}
 * ({@link #NEGATION_AUXILIARIES}). 한국어 부정은 어미에 후치하므로 서술어 어간만 읽으면
 * 부정문이 긍정으로 확정된다. 이 표지가 있으면 극성을 해소하지 않고 UNKNOWN 으로 닫는다
 * (이슈 #504).
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
     *
     * <p><b>{@code 있지}·{@code 없지} 는 제외한다 (이슈 #504).</b> 다른 항목과 달리 {@code -지}
     * 는 종결어미가 아니라 부정 보조용언 {@code 않다} 를 이끄는 보조적 연결어미이기도 하다.
     * 마커로 두면 {@code "있지 않아요"}(= 없다)가 긍정으로 확정된다.
     */
    private static final List<String> YES_MARKERS = List.of(
            "있어", "있습", "있다", "있음", "있네", "있죠", "있는데",
            "그래", "맞아", "정했", "준비", "구했", "yes");

    /** 한 음절 인정어. 다른 단어의 일부로 흔히 나타나서(반응·언니네) 서두에서만 인정한다. */
    private static final List<String> YES_PREFIXES = List.of("네", "예", "응");

    /** 문장 어디에 있어도 부정으로 보는 마커. 종결형만 인정한다 (YES 쪽 "있"/"없" 과 같은 이유). */
    private static final List<String> NO_MARKERS = List.of(
            "없어", "없습", "없다", "없음", "없네", "없죠", "없는데",
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

    /**
     * 부정 보조용언 {@code 않다} 를 이끄는 연결어미 (이슈 #504). 비한글 제거 후 형태다.
     *
     * <p>한국어 부정은 어미에 후치한다 — {@code -지 않다}. 그래서 {@code -지} 는 종결어미가
     * 아니라 뒤따르는 {@code 않다} 에 극성을 넘기는 자리이고, {@code 있지}·{@code 없지} 를
     * 종결형 마커로 읽으면 {@code "있지 않아요"}(= 없다)가 긍정으로 확정된다.
     * {@code IMMEDIATE_SUPPORT} 단계에서 그 오판독은 곁에 아무도 없다고 답한 사용자를
     * {@code COMPLETED} 로 종결시키고 "그 사람에게 연락하라"고 안내한다 — 안전의 반대 방향이다.
     *
     * <p><b>해소하지 않고 UNKNOWN 으로 닫는다.</b> 이중부정({@code "없지 않아요"} = 있다)까지
     * 정확히 풀려면 선행 서술어를 읽어야 하는데, 그 판단을 위기 경로에 넣기 전에 임상·언어
     * 검토가 필요하다. UNKNOWN 은 상태기계가 고정 handoff 로 처리하고,
     * {@code IMMEDIATE_SUPPORT} 에서는 NO 와 도착지가 같아 손실이 없다. 나머지 세 단계에서는
     * 핫라인 우선 연결로 한 단계 보수화되는 방향이다.
     */
    private static final List<String> NEGATION_AUXILIARIES = List.of(
            "지않", "진않", "지는않", "지가않", "지도않", "질않");

    public CrisisAnswer parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CrisisAnswer.UNKNOWN;
        }

        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^가-힣a-z]", "");

        // 극성이 뒤집히는 자리이므로 마커 스캔보다 먼저 본다. 마커를 먼저 읽으면
        // 부정문 안의 서술어 어간이 그 문장의 답으로 확정된다.
        if (containsAny(normalized, NEGATION_AUXILIARIES)) {
            return CrisisAnswer.UNKNOWN;
        }

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
