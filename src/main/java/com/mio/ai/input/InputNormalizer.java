package com.mio.ai.input;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Pattern;

@Component
public class InputNormalizer {

    private static final String COMPATIBILITY_LEADS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String COMPATIBILITY_VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    private static final String COMPATIBILITY_TAILS = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";
    private static final String SAFETY_SEPARATOR_CLASS = "[\\s\\p{P}\\p{S}\\p{M}\\p{Z}\\p{C}]+";
    private static final Pattern SAFETY_SEPARATORS = Pattern.compile(SAFETY_SEPARATOR_CLASS);
    private static final Pattern BETWEEN_COMPATIBILITY_JAMO = Pattern.compile(
            "(?<=[ㄱ-ㅎㅏ-ㅣ])" + SAFETY_SEPARATOR_CLASS + "(?=[ㄱ-ㅎㅏ-ㅣ])");
    private static final Pattern SEPARATED_HANGUL_RUN = Pattern.compile(
            "(?:[가-힣]" + SAFETY_SEPARATOR_CLASS + "){3,}[가-힣]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public String normalize(String text) {
        if (text == null) return "";
        return text.strip()
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    /**
     * 안전 규칙 매칭에만 쓰는 비가역 정규화본을 만든다.
     *
     * <p>일반 {@link #normalize(String)} 결과는 Judge·메모리·온톨로지에도 전달되므로 구두점과
     * 표현을 보존한다. 자모 조합과 삽입 구분자 복원은 사용자 문장을 훼손하지 않도록 이 별도
     * 뷰에서만 수행한다. 모든 구두점을 지우지 않고, 호환 자모 사이 또는 네 음절 이상에 반복
     * 삽입된 구분자만 접어 {@code "자, 살펴볼까요"} 같은 실제 문법 경계를 보존한다.
     */
    public String normalizeForSafetyMatching(String text) {
        String jamoJoined = BETWEEN_COMPATIBILITY_JAMO.matcher(normalize(text)).replaceAll("");
        String composed = composeCompatibilityJamo(jamoJoined);
        String compatibilityNormalized = Normalizer.normalize(composed, Normalizer.Form.NFKC);
        String restored = collapseSeparatedHangulRuns(compatibilityNormalized);
        String compact = WHITESPACE.matcher(restored).replaceAll("");
        return compact
                .replace("쥭", "죽")
                .replace("시퍼", "싶어");
    }

    private String collapseSeparatedHangulRuns(String text) {
        return SEPARATED_HANGUL_RUN.matcher(text).replaceAll(match ->
                SAFETY_SEPARATORS.matcher(match.group()).replaceAll(""));
    }

    private String composeCompatibilityJamo(String text) {
        StringBuilder result = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            int lead = COMPATIBILITY_LEADS.indexOf(text.charAt(index));
            int vowel = index + 1 < text.length()
                    ? COMPATIBILITY_VOWELS.indexOf(text.charAt(index + 1)) : -1;
            if (lead < 0 || vowel < 0) {
                result.append(text.charAt(index++));
                continue;
            }

            int tail = resolveTail(text, index + 2);
            result.append((char) (0xAC00 + (lead * 21 + vowel) * 28 + tail));
            index += tail == 0 ? 2 : 3;
        }
        return result.toString();
    }

    private int resolveTail(String text, int candidateIndex) {
        if (candidateIndex >= text.length()) {
            return 0;
        }
        char candidate = text.charAt(candidateIndex);
        int tail = COMPATIBILITY_TAILS.indexOf(candidate);
        if (tail <= 0) {
            return 0;
        }

        boolean startsNextSyllable = COMPATIBILITY_LEADS.indexOf(candidate) >= 0
                && candidateIndex + 1 < text.length()
                && COMPATIBILITY_VOWELS.indexOf(text.charAt(candidateIndex + 1)) >= 0;
        return startsNextSyllable ? 0 : tail;
    }
}
