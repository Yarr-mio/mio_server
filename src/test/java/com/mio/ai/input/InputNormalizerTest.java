package com.mio.ai.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InputNormalizerTest {

    private final InputNormalizer normalizer = new InputNormalizer();

    @Test
    @DisplayName("일반 정규화는 사용자 문장의 구두점을 보존한다")
    void conversationalNormalizationPreservesPunctuation() {
        assertThat(normalizer.normalize("  죽.고.싶.다  "))
                .isEqualTo("죽.고.싶.다");
    }

    @Test
    @DisplayName("안전 매칭 정규화는 호환 자모를 음절로 조합한다")
    void safetyNormalizationComposesCompatibilityJamo() {
        assertThat(normalizer.normalizeForSafetyMatching("ㅈㅜㄱ고싶다"))
                .isEqualTo("죽고싶다");
        assertThat(normalizer.normalizeForSafetyMatching("ㅈㅏㅅㅏㄹ 생각중"))
                .isEqualTo("자살생각중");
        assertThat(normalizer.normalizeForSafetyMatching("ㅈㅏ해 하고싶어"))
                .isEqualTo("자해하고싶어");
    }

    @Test
    @DisplayName("안전 매칭 정규화는 어휘 사이 구두점과 반복 기호를 제거한다")
    void safetyNormalizationRemovesInsertedPunctuation() {
        assertThat(normalizer.normalizeForSafetyMatching("죽.고.싶.다"))
                .isEqualTo("죽고싶다");
        assertThat(normalizer.normalizeForSafetyMatching("죽~~고~~싶~~다"))
                .isEqualTo("죽고싶다");
    }

    @Test
    @DisplayName("안전 매칭 정규화는 알려진 위기어 표기 변형을 정규형으로 바꾼다")
    void safetyNormalizationCanonicalizesKnownCrisisVariants() {
        assertThat(normalizer.normalizeForSafetyMatching("쥭고싶다"))
                .isEqualTo("죽고싶다");
        assertThat(normalizer.normalizeForSafetyMatching("죽고시퍼"))
                .isEqualTo("죽고싶어");
        assertThat(normalizer.normalizeForSafetyMatching("kill myself 하고 싶어"))
                .isEqualTo("killmyself하고싶어");
    }
}
