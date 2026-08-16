package com.mio.ai.qa;

/**
 * 모델을 부르지 않고 토큰 수를 <b>추정</b>한다.
 *
 * <p>비용 추정 테스트는 API 키 없이 돌아야 한다. 그러려면 실제 프롬프트가 몇 토큰인지를
 * 호출 없이 알아야 하는데, 정확한 값은 tokenizer 를 실행해야 나온다. tokenizer 의존성을
 * 새로 들이는 대신 문자 종류별 비율로 근사하고, <b>근사라는 사실과 오차 범위를 결과에 같이
 * 싣는다.</b> 단일 숫자로 내면 그 숫자가 견적서가 되고, 견적서는 틀렸을 때 책임을 남긴다.
 *
 * <p>비율의 근거는 o200k_base(gpt-4o 계열) 의 일반적 관측이다.
 *
 * <ul>
 *   <li>ASCII·라틴 문자: 약 4자당 1토큰</li>
 *   <li>한글 음절: 약 1.2자당 1토큰 (자주 쓰는 음절은 1토큰, 드문 음절은 2~3바이트 조각)</li>
 *   <li>그 외(기호·CJK·이모지): 약 1.5자당 1토큰</li>
 * </ul>
 *
 * <p>실제 실행이 끝나면 이 추정은 필요 없다 — {@link CellTokenLedger} 가 제공자가 보고한
 * 실측 토큰을 들고 있다. 추정은 "돌리기 전에 청구서를 본다" 는 용도에만 쓰고, 실행 후
 * 리포트는 실측만 쓴다.
 */
final class CellTokenEstimator {

    /** 추정 오차 하한 배수 — 실측이 추정보다 작을 수 있는 폭. */
    static final double LOWER_MULTIPLIER = 0.75;
    /** 추정 오차 상한 배수. 견적은 상한 쪽으로 읽는 것이 안전하다. */
    static final double UPPER_MULTIPLIER = 1.40;

    private static final double ASCII_CHARS_PER_TOKEN = 4.0;
    private static final double HANGUL_CHARS_PER_TOKEN = 1.2;
    private static final double OTHER_CHARS_PER_TOKEN = 1.5;

    /** 메시지 하나당 채팅 포맷 오버헤드(role·구분자). OpenAI 문서의 관례값. */
    private static final int PER_MESSAGE_OVERHEAD = 4;

    private CellTokenEstimator() {
    }

    /** 텍스트 한 덩이의 추정 토큰 수. */
    static long tokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double ascii = 0;
        double hangul = 0;
        double other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 128) {
                ascii++;
            } else if (isHangul(cp)) {
                hangul++;
            } else {
                other++;
            }
        }
        return Math.round(ascii / ASCII_CHARS_PER_TOKEN
                + hangul / HANGUL_CHARS_PER_TOKEN
                + other / OTHER_CHARS_PER_TOKEN);
    }

    /** 채팅 요청 하나의 추정 prompt 토큰 수. */
    static long promptTokens(java.util.List<com.mio.ai.llm.LlmRequest.Message> messages) {
        long total = 0;
        for (var message : messages) {
            total += tokens(message.content()) + PER_MESSAGE_OVERHEAD;
        }
        return total + 3;
    }

    private static boolean isHangul(int cp) {
        return (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0x1100 && cp <= 0x11FF)
                || (cp >= 0x3130 && cp <= 0x318F);
    }
}
