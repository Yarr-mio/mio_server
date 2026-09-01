package com.mio.ai.memory.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 민감도 cap 판정의 진리표를 고정한다.
 *
 * <p>이 술어는 사용자가 비공개로 표시한 기억이 프롬프트로 나가는지를 가르는 마지막 관문이고,
 * 틀려도 예외가 아니라 <b>조용한 유출</b>로 나타난다. 검색 랭커와 컨텍스트 새니타이저가
 * 각자 복사본을 들고 있던 동안 이 표를 검증하는 테스트가 하나도 없었다.
 */
class SensitivityCapTest {

    @Nested
    @DisplayName("cap 이 restricted 면")
    class RestrictedCap {

        @ParameterizedTest
        @ValueSource(strings = {"normal", "sensitive", "restricted"})
        @DisplayName("모든 민감도를 통과시킨다")
        void allowsEverything(String sensitivity) {
            assertThat(SensitivityCap.allows("restricted", sensitivity)).isTrue();
        }
    }

    @Nested
    @DisplayName("cap 이 sensitive 면")
    class SensitiveCap {

        @ParameterizedTest
        @ValueSource(strings = {"normal", "sensitive"})
        @DisplayName("restricted 를 제외한 나머지를 통과시킨다")
        void allowsUpToSensitive(String sensitivity) {
            assertThat(SensitivityCap.allows("sensitive", sensitivity)).isTrue();
        }

        @Test
        @DisplayName("restricted 는 막는다")
        void blocksRestricted() {
            assertThat(SensitivityCap.allows("sensitive", "restricted")).isFalse();
        }
    }

    @Nested
    @DisplayName("cap 이 normal 이거나 모르는 값이면")
    class NormalOrUnknownCap {

        @ParameterizedTest
        @CsvSource({
                "normal,  normal,     true",
                "normal,  sensitive,  false",
                "normal,  restricted, false",
                // 모르는 cap 은 가장 좁게 해석한다 — 오타가 권한 상승이 되면 안 된다.
                "'',      sensitive,  false",
                "unknown, sensitive,  false",
                "unknown, normal,     true",
        })
        @DisplayName("normal 만 통과시킨다")
        void allowsOnlyNormal(String cap, String sensitivity, boolean expected) {
            assertThat(SensitivityCap.allows(cap, sensitivity)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("null 처리")
    class NullHandling {

        @ParameterizedTest
        @ValueSource(strings = {"normal", "sensitive", "restricted"})
        @DisplayName("cap 이 null 이면 아무것도 통과시키지 않는다 (fail-closed)")
        void nullCapBlocksEverything(String sensitivity) {
            assertThat(SensitivityCap.allows(null, sensitivity)).isFalse();
        }

        @Test
        @DisplayName("민감도가 null 이면 normal 로 본다")
        void nullSensitivityIsNormal() {
            assertThat(SensitivityCap.allows("normal", null)).isTrue();
            assertThat(SensitivityCap.allows("restricted", null)).isTrue();
        }
    }
}
