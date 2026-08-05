package com.mio.ai.delivery;

import java.util.ArrayList;
import java.util.List;

/**
 * 스트림 청크를 <b>검사 가능한 단위</b>로 자른다 (이슈 #306, 로드맵 §5.6).
 *
 * <p>기존 전달은 글자 수(200자)를 기준으로 사후 검사했다. 그 방식은 검사 간격을 줄여도
 * 첫 노출 문제가 남는다 — 검사 전에 이미 나간 글자는 되돌릴 수 없기 때문이다. 기준은
 * 글자 수가 아니라 승인할 수 있는 단위여야 한다.
 *
 * <p>이 클래스는 자르기만 한다. 승인 여부는 호출부가 결정한다 — 판정 로직을 여기 두면
 * 안전 검사가 문자열 처리 코드에 숨는다.
 */
public final class ApprovedUnitBuffer {

    /**
     * 종결 부호가 오지 않아도 이 길이에서 한 단위로 끊는다.
     *
     * <p>무한정 모으면 긴 문단 하나가 전부 끝날 때까지 사용자가 아무것도 못 본다. 검사 없이
     * 내보내는 것과 검사 후 늦게 내보내는 것 중에서는 후자를 택하되, 그 지연에 상한을 둔다.
     *
     * <p>값은 최악의 대기 시간에서 역산했다. {@code DeliveryExposureQaTest} 실측 기준 정상
     * 응답의 첫 단위는 11~22자라 이 상한에 닿지 않는다. 상한이 실제로 발동하는 것은 종결
     * 부호가 없는 비정상 출력뿐이고, 그때 사용자는 이 길이만큼을 빈 화면으로 기다린다.
     * 한국어 스트리밍이 초당 30자 수준이므로 160자는 5초가 넘는다 — 그래서 80자로 둔다.
     * 단위가 짧아져 검사 횟수는 늘지만 검사는 정규식이라 비용이 낮고, 보장은 그대로다.
     */
    private static final int DEFAULT_MAX_UNIT_CHARS = 80;

    /** 종결 부호. 홑화살 줄임표(…)도 한국어 대화에서 문장 끝으로 쓰인다. */
    private static final String SENTENCE_END = ".!?。！？…\n";

    private final int maxUnitChars;
    private final StringBuilder pending = new StringBuilder();

    public ApprovedUnitBuffer() {
        this(DEFAULT_MAX_UNIT_CHARS);
    }

    public ApprovedUnitBuffer(int maxUnitChars) {
        if (maxUnitChars <= 0) {
            throw new IllegalArgumentException("maxUnitChars must be positive: " + maxUnitChars);
        }
        this.maxUnitChars = maxUnitChars;
    }

    /**
     * 청크를 넣고 검사 준비가 된 단위들을 순서대로 반환한다.
     *
     * <p>반환된 단위를 이어 붙이면 입력과 정확히 같다 — 전달 과정에서 글자가 사라지거나
     * 순서가 바뀌면 사용자가 읽는 문장이 깨진다.
     */
    public List<String> offer(String chunk) {
        if (chunk != null && !chunk.isEmpty()) {
            pending.append(chunk);
        }
        List<String> units = new ArrayList<>();
        int cut;
        while ((cut = nextCut()) > 0) {
            units.add(pending.substring(0, cut));
            pending.delete(0, cut);
        }
        return units;
    }

    /** 스트림이 끝난 뒤 남은 텍스트. 종결 부호가 없어도 마지막 단위로 검사해야 한다. */
    public String drain() {
        String remaining = pending.toString();
        pending.setLength(0);
        return remaining;
    }

    /**
     * 다음 단위의 끝 위치. 없으면 0.
     *
     * <p>종결 부호 뒤에 붙는 닫는 따옴표·공백은 같은 단위에 포함한다. 그렇지 않으면
     * {@code "괜찮아요."} 다음 단위가 {@code " "} 하나가 되어 검사 횟수만 늘어난다.
     */
    private int nextCut() {
        for (int i = 0; i < pending.length(); i++) {
            if (SENTENCE_END.indexOf(pending.charAt(i)) >= 0) {
                int end = i + 1;
                while (end < pending.length() && isTrailing(pending.charAt(end))) {
                    end++;
                }
                return end;
            }
        }
        return pending.length() >= maxUnitChars ? safeCut(maxUnitChars) : 0;
    }

    /**
     * 강제 절단 위치를 코드 포인트 경계로 맞춘다.
     *
     * <p>Java 문자열은 UTF-16 코드 단위라, 이모지처럼 BMP 밖 문자는 두 개의 char 로 저장된다.
     * 숫자 인덱스로 그대로 자르면 서로게이트 쌍이 두 단위로 쪼개져 양쪽 다 깨진 문자가 된다.
     * 종결 부호 기준 절단은 이 문제가 없다 — 종결 부호는 전부 BMP 문자다.
     */
    private int safeCut(int index) {
        return Character.isHighSurrogate(pending.charAt(index - 1)) ? index - 1 : index;
    }

    private boolean isTrailing(char c) {
        return c == ' ' || c == '"' || c == '\'' || c == ')' || c == ']'
                || c == '”' || c == '’' || c == '\n';
    }
}
