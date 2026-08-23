package com.mio.ai.crisis;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 예·아니오 고정 질문의 명시적 답만 구조화한다. 자유 서술을 위험 판단으로 추론하지 않는다.
 *
 * <p>마커는 문장 위치와 무관하게 양방향 모두 탐지한다. 서두만 읽으면 "네, 없어요"가 YES 로,
 * "아니, 이미 정했어"가 NO 로 확정된다 — 후자는 준비 완료 진술을 지우는 방향의 오류다.
 * 긍정·부정 마커가 함께 있으면 확정하지 않고 UNKNOWN 을 반환하며, 상태기계가 UNKNOWN 을
 * 고정 handoff 로 fail-closed 처리한다.
 *
 * <p>마커 스캔보다 먼저 보는 것이 하나 있다 — 존재 서술어 어간에 {@code -지} 계열 어미가
 * 붙은 형태({@link #EXISTENCE_POLARITY_BLOCKERS}). 한국어 부정은 어미에 후치하므로
 * ({@code -지 않다}) 어간만 읽으면 부정문이 긍정으로 확정된다. 이 형태가 있으면 극성을
 * 해소하지 않고 UNKNOWN 으로 닫는다 (이슈 #504).
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
     * <p>{@code 있지}·{@code 없지} 는 그대로 둔다. {@code -지} 가 부정 보조용언 {@code 않다} 를
     * 이끄는 자리이기도 하지만, 그 충돌은 {@link #EXISTENCE_POLARITY_BLOCKERS} 가 마커 스캔보다
     * 먼저 걸러낸다 (이슈 #504). 마커를 지우면 {@code "있지"}·{@code "있지요"} 같은 평범한 구어
     * 긍정을 잃고, {@code "없지만"} 이 부정 증거를 통째로 잃어 YES 로 뒤집힌다.
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

    /** 존재 서술어 어간. {@code -지} 계열 어미와 결합할 때 극성이 모호해지는 두 형태다. */
    private static final List<String> EXISTENCE_STEMS = List.of("있", "없");

    /**
     * {@code -지} 계열 어미 — 앞의 존재 서술어를 <b>답으로 확정할 수 없게</b> 만드는 형태.
     *
     * <p>{@code -지 않다}(부정 보조용언)와 {@code -지만}(양보 연결어미) 둘 다 포함한다.
     * 종류는 다르지만 결과가 같다 — 어느 쪽이든 {@code 있지}·{@code 없지} 를 종결형 답변으로
     * 읽으면 문장의 뜻과 반대가 된다.
     */
    private static final List<String> POLARITY_SUFFIXES = List.of(
            "지않", "진않", "지는않", "지가않", "지도않", "질않", "지를않", "지아니", "지만");

    /**
     * 존재 서술어 어간에 {@code -지} 계열 어미가 붙은 형태 (이슈 #504).
     *
     * <p>한국어 부정은 어미에 후치한다({@code -지 않다}). 그래서 서술어 어간만 읽으면
     * {@code "있지 않아요"}(= 없다)가 긍정으로, {@code "없지만 …"}(= 없다)이 부정 증거 없이
     * 남는다. {@code IMMEDIATE_SUPPORT} 단계에서 그 오판독은 곁에 아무도 없다고 답한 사용자를
     * {@code COMPLETED} 로 종결시키고 "그 사람에게 연락하라"고 안내한다 — 안전의 반대 방향이다.
     *
     * <p><b>어간에 붙은 형태만 본다.</b> {@code -지 않} 전체를 차단하면
     * {@code "계획은 세우지 않았지만 도구는 구했어요"} 처럼 <b>다른</b> 서술어가 부정되고 준비
     * 완료 진술이 따라오는 문장까지 삼켜, 이 클래스가 명시한 규율(준비 행동 동사는 부정 서두
     * 뒤에 나와도 긍정 증거다)을 깨뜨린다. 충돌은 {@code 있}·{@code 없} 어간에만 있으므로
     * 그 자리만 막는다.
     *
     * <p><b>극성을 해소하지 않고 UNKNOWN 으로 닫는다.</b> 이중부정
     * ({@code "없지 않아요"} = 있다)까지 정확히 풀려면 선행 서술어를 읽어야 하고, 그 판단을
     * 위기 경로에 넣기 전에 임상·언어 검토가 필요하다. UNKNOWN 은 상태기계가 고정 handoff 로
     * 처리하며 {@code IMMEDIATE_SUPPORT} 에서는 NO 와 도착지가 같다. 나머지 단계에서는
     * 핫라인 우선으로 보수화되는 방향이다 — 단 {@code MEANS_ACCESS} 는 YES·NO 도착지가 같아
     * (둘 다 {@code IMMEDIATE_SUPPORT}) UNKNOWN 이 지원 인물 확인 단계를 건너뛰게 만든다.
     */
    private static final List<String> EXISTENCE_POLARITY_BLOCKERS = EXISTENCE_STEMS.stream()
            .flatMap(stem -> POLARITY_SUFFIXES.stream().map(suffix -> stem + suffix))
            .toList();

    /** 매 턴 호출되는 경로라 정규식을 미리 컴파일한다. */
    private static final Pattern NON_ANSWER_CHARS = Pattern.compile("[^가-힣a-z]");

    public CrisisAnswer parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CrisisAnswer.UNKNOWN;
        }

        String normalized = NON_ANSWER_CHARS
                .matcher(raw.toLowerCase(Locale.ROOT)).replaceAll("");

        // 극성이 뒤집히는 자리이므로 마커 스캔보다 먼저 본다. 마커를 먼저 읽으면
        // 부정문 안의 서술어 어간이 그 문장의 답으로 확정된다.
        if (containsAny(normalized, EXISTENCE_POLARITY_BLOCKERS)) {
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

    private static boolean containsAny(String value, List<String> markers) {
        for (String marker : markers) {
            if (value.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
