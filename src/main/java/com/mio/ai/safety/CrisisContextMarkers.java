package com.mio.ai.safety;

import java.util.Set;

/**
 * 위기 키워드가 1인칭 현재 진술이 아님을 시사하는 결정론적 맥락 마커 (이슈 #255).
 *
 * <p>키워드 부분일치만으로는 "친구가 죽고싶다고 했어요"(3인칭), "자살은 답이 아니라고 생각해요"(부정),
 * "예전엔 죽고싶었는데 지금은 괜찮아요"(과거 회복), "노래 가사에 죽고싶다는 말이"(인용)를 구분할 수 없다.
 *
 * <p>이 클래스는 위기를 <b>해제하지 않는다</b>. 마커가 발견되면 {@link SafetyL1}이 확정 위기를
 * 검증 대기 상태로 강등할 뿐이며, 강등된 발화는 반드시 InputJudge 판정을 거친다.
 * 따라서 마커 판정이 틀려도 안전한 방향으로 실패한다.
 */
final class CrisisContextMarkers {

    /** 위기 발화의 주체가 사용자 본인이 아님을 시사 */
    private static final Set<String> THIRD_PERSON = Set.of(
            "친구가", "친구는", "동생이", "동생은", "누나가", "형이", "언니가", "오빠가",
            "엄마가", "아빠가", "그사람이", "그분이", "지인이", "후배가", "선배가", "동료가",
            "다고했", "다고해", "다고하", "라고했", "라고해", "라고하", "하더라고", "한대", "했대"
    );

    /** 인용·매체·학습 맥락 */
    private static final Set<String> QUOTATION = Set.of(
            "가사", "노래", "영화", "드라마", "뉴스", "기사에", "기사를", "책에서", "웹툰",
            "소설", "다큐", "방송", "유튜브", "댓글", "캠페인", "수업에서", "강의",
            "리포트", "과제로", "사례", "예방"
    );

    /** 위기 사고를 부정하는 진술 */
    private static final Set<String> NEGATION = Set.of(
            "지않", "지도않", "절대", "아니라고", "아니에요", "아닙니다", "아니야",
            "생각안", "해본적없", "한적없", "적이없", "없다고", "안한다", "안해요"
    );

    /** 과거 시점 지시어 */
    private static final Set<String> PAST = Set.of(
            "예전엔", "예전에", "옛날에", "작년에", "재작년", "그때는", "그때만", "한때",
            "어릴때", "전에는"
    );

    /** 회복을 단독으로 시사하는 강한 표현 */
    private static final Set<String> RECOVERY_STRONG = Set.of(
            "괜찮아졌", "나아졌", "벗어났", "극복했", "회복했", "이겨냈", "좋아졌"
    );

    /** 과거 지시어와 함께 있을 때만 회복으로 읽히는 약한 표현 */
    private static final Set<String> RECOVERY_WEAK = Set.of(
            "지금은", "이제는", "요즘은", "후회돼", "후회했"
    );

    /**
     * 1인칭 위기 진술. 다른 맥락 마커가 있어도 이것이 함께 있으면 강등하지 않는다.
     * 예: "친구가 죽고싶다고 했는데 사실 나도 죽고 싶어요"
     */
    private static final Set<String> FIRST_PERSON_CRISIS = Set.of(
            "나도죽", "저도죽", "나도자해", "나도자살", "저도자해", "저도자살",
            "나도목숨", "저도목숨", "나도사라지", "저도사라지",
            "나는죽고싶", "저는죽고싶", "내가죽고싶", "제가죽고싶", "나죽고싶", "저죽고싶"
    );

    private CrisisContextMarkers() {
    }

    /**
     * @param compactMessage 공백이 제거된 소문자 메시지
     * @return 검증이 필요한 맥락 종류. 강등 대상이 아니면 {@code null}
     */
    static String detect(String compactMessage) {
        if (containsAny(compactMessage, FIRST_PERSON_CRISIS)) {
            return null;
        }
        if (containsAny(compactMessage, THIRD_PERSON)) {
            return "third_person";
        }
        if (containsAny(compactMessage, QUOTATION)) {
            return "quotation";
        }
        if (containsAny(compactMessage, NEGATION)) {
            return "negation";
        }
        if (isPastRecovery(compactMessage)) {
            return "past_recovery";
        }
        return null;
    }

    private static boolean isPastRecovery(String compactMessage) {
        if (containsAny(compactMessage, RECOVERY_STRONG)) {
            return true;
        }
        return containsAny(compactMessage, PAST) && containsAny(compactMessage, RECOVERY_WEAK);
    }

    private static boolean containsAny(String compactMessage, Set<String> markers) {
        return markers.stream().anyMatch(compactMessage::contains);
    }
}
