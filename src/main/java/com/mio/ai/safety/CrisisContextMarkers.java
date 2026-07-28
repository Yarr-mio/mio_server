package com.mio.ai.safety;

import java.util.Collection;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 위기 키워드가 1인칭 현재 진술이 아님을 시사하는 결정론적 맥락 마커 (이슈 #255).
 *
 * <p>키워드 부분일치만으로는 "친구가 죽고싶다고 했어요"(3인칭), "자살은 답이 아니라고 생각해요"(부정),
 * "예전엔 죽고싶었는데 지금은 괜찮아요"(과거 회복), "노래 가사에 죽고싶다는 말이"(인용)를 구분할 수 없다.
 *
 * <p><b>마커는 위기 키워드 근처에서만 유효하다.</b> 메시지 전체를 훑으면
 * "엄마가 늦는다고 해서 혼자 있는데 나 진짜 죽고싶어"처럼 앞 절의 3인칭 서술이 뒤 절의 1인칭 위기
 * 진술을 강등시킨다. 그래서 매칭된 위기 키워드 주변 창(window) 안의 마커만 인정하고, 마커가 붙지
 * 않은 위기 절이 하나라도 있으면 강등하지 않는다.
 *
 * <p>이 클래스는 위기를 <b>해제하지 않는다</b>. 마커가 발견되면 {@link SafetyL1}이 확정 위기를
 * 검증 대기 상태로 강등할 뿐이며, 강등된 발화는 반드시 InputJudge 판정을 거친다.
 * 따라서 마커 판정이 틀려도 안전한 방향으로 실패한다.
 */
final class CrisisContextMarkers {

    /**
     * 위기 키워드 앞뒤로 마커를 인정할 문자 수. 공백이 제거된 문자열 기준이다.
     *
     * <p>3인칭 주어("친구가~")는 키워드 앞에, 인용 어미("~다고 했")는 키워드 뒤에 붙으므로 양방향으로 본다.
     * 창을 넓히면 무관한 절의 마커가 새어 들어와 진짜 위기를 놓치고(FN), 좁히면 맥락 발화가 즉시
     * 위기로 확정된다(FP). 코퍼스 기준으로 조정한 값이다.
     */
    private static final int WINDOW_BEFORE = 14;
    private static final int WINDOW_AFTER = 12;

    /** 위기 발화의 주체가 사용자 본인이 아님을 시사 */
    private static final Set<String> THIRD_PERSON = Set.of(
            "친구가", "친구는", "동생이", "동생은", "누나가", "형이", "언니가", "오빠가",
            "엄마가", "아빠가", "그사람이", "그분이", "지인이", "후배가", "선배가", "동료가",
            "다고했", "다고해", "다고하", "라고했", "라고해", "라고하", "하더라고", "한대", "했대"
    );

    /** 인용·매체·학습 맥락 */
    private static final Set<String> QUOTATION = Set.of(
            "가사", "영화", "드라마", "뉴스", "기사에", "기사를", "책에서", "웹툰",
            "소설", "다큐", "방송", "유튜브", "댓글", "캠페인", "수업에서", "강의",
            "리포트", "과제로"
    );

    /**
     * 위기 사고를 부정하는 진술.
     *
     * <p>부정어 단독("전혀없", "절대")은 "희망이 전혀 없어요", "이제 절대 못 버티겠어" 같은 절망 표현과
     * 구분되지 않으므로 등록하지 않는다. 반드시 사고·행위를 가리키는 어절과 붙은 형태만 등록한다.
     */
    private static final Set<String> NEGATION = Set.of(
            "죽고싶지않", "죽지않", "자해하지않", "자살하지않", "그런생각하지않", "생각하지않",
            "지도않", "아니라고", "아니에요", "아닙니다", "아니야",
            "생각안", "생각도안", "그런생각안", "생각이전혀없", "생각전혀없",
            "할생각이없", "생각해본적없", "해본적없", "한적없", "적이없",
            "없다고", "안한다", "안해요"
    );

    static final String THIRD_PERSON_MARKER = "third_person";
    static final String QUOTATION_MARKER = "quotation";
    static final String NEGATION_MARKER = "negation";
    static final String RECOVERY_MARKER = "past_recovery";

    /**
     * 실제 회복을 가리키는 표현.
     *
     * <p>"지금은"·"이제는" 같은 시점 대조어는 등록하지 않는다. 한국어의 "예전엔 X, 지금은 Y" 구문은
     * 악화("예전엔 좋았는데 지금은 진짜 죽고싶어")에도 똑같이 쓰이므로, 시점 대조어를 회복 신호로
     * 읽으면 진행 중인 위기를 과거 회복담으로 오인한다. 회복은 서술어로만 판정한다.
     */
    private static final Set<String> RECOVERY = Set.of(
            "괜찮아졌", "나아졌", "벗어났", "극복했", "회복했", "이겨냈", "좋아졌",
            "잘지내", "잘지냈", "잘넘겼", "넘겼어", "후회돼", "후회했", "후회하"
    );

    /**
     * 1인칭 위기 진술. 다른 맥락 마커가 있어도 이것이 함께 있으면 강등하지 않는다.
     *
     * <p>주어와 서술어 사이에 부사가 끼는 형태("나 진짜 죽고싶어")까지 잡아야 한다. 고정 부분문자열
     * 목록으로는 부사 하나에 뚫리므로 패턴으로 처리한다. 조사 "한테"·"보고" 등이 오는 목적격
     * ("친구가 나한테 죽고싶다고 했어요")은 부사 목록에 없으므로 매칭되지 않는다.
     */
    private static final Pattern FIRST_PERSON_CRISIS = Pattern.compile(
            "(나는|나도|내가|저는|저도|제가|나|저)"
                    + "(진짜|정말|너무|그냥|자꾸|계속|요즘|오늘|지금|매일|좀|많이|이제|솔직히|사실|자꾸만)*"
                    + "(죽고싶|죽어버리|자살|자해|목숨을끊|스스로목숨|사라지고싶|숨지고싶)"
    );

    private CrisisContextMarkers() {
    }

    /**
     * @param compactMessage  공백이 제거된 소문자 메시지
     * @param matchedKeywords 이 메시지에서 매칭된 위기 키워드 전체
     * @return 검증이 필요한 맥락 종류. 강등 대상이 아니면 {@code null}
     */
    static String detect(String compactMessage, Collection<String> matchedKeywords) {
        boolean firstPersonCrisis = FIRST_PERSON_CRISIS.matcher(compactMessage).find();
        // 회복 서술어는 "예전엔 ~했는데 지금은 괜찮아졌어요"처럼 문장 끝에 오므로 키워드 창 밖에 있는
        // 것이 정상이다. 이 유형만 문장 전체로 판정한다.
        boolean recoveryNarrative = containsAny(compactMessage, RECOVERY);

        String detected = null;
        for (String keyword : matchedKeywords) {
            for (int at = compactMessage.indexOf(keyword); at >= 0;
                 at = compactMessage.indexOf(keyword, at + 1)) {

                String marker = markerNear(compactMessage, at, keyword.length());

                // 본인이 위기를 진술하고 있으면 "남 얘기" 계열 마커는 무효다.
                // 부정("저는 자해할 생각이 전혀 없어요")은 1인칭 주어가 곧 부정의 주체이므로
                // 이 예외를 적용하지 않는다.
                if (firstPersonCrisis && (THIRD_PERSON_MARKER.equals(marker) || QUOTATION_MARKER.equals(marker))) {
                    marker = null;
                }
                if (marker == null && recoveryNarrative) {
                    marker = RECOVERY_MARKER;
                }
                if (marker == null) {
                    // 마커 없이 등장한 위기 절이 있다 — 강등하지 않는다.
                    return null;
                }
                if (detected == null) {
                    detected = marker;
                }
            }
        }
        return detected;
    }

    /** 키워드 주변 창 안에서만 마커를 찾는다. */
    private static String markerNear(String compactMessage, int keywordAt, int keywordLength) {
        int from = Math.max(0, keywordAt - WINDOW_BEFORE);
        int to = Math.min(compactMessage.length(), keywordAt + keywordLength + WINDOW_AFTER);
        String window = compactMessage.substring(from, to);

        if (containsAny(window, NEGATION)) {
            return NEGATION_MARKER;
        }
        if (containsAny(window, THIRD_PERSON)) {
            return THIRD_PERSON_MARKER;
        }
        if (containsAny(window, QUOTATION)) {
            return QUOTATION_MARKER;
        }
        return null;
    }

    private static boolean containsAny(String window, Set<String> markers) {
        return markers.stream().anyMatch(window::contains);
    }
}
