package com.mio.ai.safety;

import com.mio.ai.moderation.ModerationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이슈 #255 — 위기 키워드 부분일치가 3인칭·인용·부정·과거 회복 발화를 위기로 오판하는 문제.
 *
 * <p>컨텍스트 마커가 있으면 hardCrisis를 hardCrisisUnverified로 강등하되,
 * riskCandidate는 유지해 InputJudge 검증으로 넘긴다(강등하되 무시하지 않는다).
 */
class SafetyL1ContextMarkerTest {

    private final SafetyL1 safetyL1 = new SafetyL1();

    private SafetyL1Result check(String message) {
        return safetyL1.check(new SafetyL1Input(
                message.toLowerCase(java.util.Locale.ROOT), List.of(), ModerationResult.clear()));
    }

    private void assertDowngraded(String message) {
        SafetyL1Result result = check(message);
        assertThat(result.hardCrisis())
                .as("'%s' 는 즉시 위기로 확정되면 안 된다", message)
                .isFalse();
        assertThat(result.hardCrisisUnverified())
                .as("'%s' 는 검증 대기 상태여야 한다", message)
                .isTrue();
        assertThat(result.riskCandidate())
                .as("'%s' 강등 시 riskCandidate는 유지되어야 InputJudge가 호출된다", message)
                .isTrue();
    }

    private void assertConfirmedCrisis(String message) {
        SafetyL1Result result = check(message);
        assertThat(result.hardCrisis())
                .as("'%s' 는 즉시 위기로 확정되어야 한다", message)
                .isTrue();
        assertThat(result.hardCrisisUnverified())
                .as("'%s' 는 검증 대기 상태가 아니어야 한다", message)
                .isFalse();
    }

    @Nested
    @DisplayName("오탐 강등 — 컨텍스트 마커가 있으면 검증으로 넘긴다")
    class Downgrade {

        @ParameterizedTest(name = "3인칭: {0}")
        @ValueSource(strings = {
                "친구가 죽고싶다고 해서 걱정돼요",
                "동생이 자해를 한 적 있어서 어떻게 도와야 할지 모르겠어요",
                "그 사람이 자살을 생각한다고 했어요",
                "후배가 죽고싶다고 하더라고요"
        })
        void thirdPerson(String message) {
            assertDowngraded(message);
        }

        @ParameterizedTest(name = "인용·매체: {0}")
        @ValueSource(strings = {
                "그 노래 가사에 죽고싶다는 말이 나와서 좀 그랬어요",
                "영화에서 자살 장면이 나와서 놀랐어요",
                "리포트 주제가 청소년 자해 예방이에요",
                "뉴스에서 자살 예방 캠페인을 봤어요",
                "상담 수업에서 자해 사례를 배웠어요"
        })
        void quotationAndMedia(String message) {
            assertDowngraded(message);
        }

        @ParameterizedTest(name = "부정: {0}")
        @ValueSource(strings = {
                "자해 같은 건 절대 생각 안 해요",
                "자살은 답이 아니라고 생각해요",
                "죽고싶다는 생각은 해본 적 없어요"
        })
        void negation(String message) {
            assertDowngraded(message);
        }

        @ParameterizedTest(name = "과거 회복: {0}")
        @ValueSource(strings = {
                "예전엔 죽고싶다고 생각했는데 지금은 많이 괜찮아졌어요",
                "작년에 자해했던 게 이제는 후회돼요, 지금은 잘 지내요",
                "상담 덕분에 자살 생각에서 벗어났어요"
        })
        void pastRecovery(String message) {
            assertDowngraded(message);
        }
    }

    @Nested
    @DisplayName("회귀 방지 — 명시적 1인칭 위기 발화는 그대로 즉시 위기다")
    class NoRegression {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "죽고싶다",
                "그냥 죽고 싶어요",
                "자해를 했어요",
                "자살을 생각하고 있어요",
                "이제 목숨을 끊고 싶어요",
                "죽어버리고 싶은 마음뿐이에요",
                "i want to end my life by suicide"
        })
        void explicitCrisisStaysHardCrisis(String message) {
            assertConfirmedCrisis(message);
        }

        @ParameterizedTest(name = "1인칭 우선: {0}")
        @ValueSource(strings = {
                "친구가 죽고싶다고 했는데 사실 나도 죽고 싶어요",
                "영화에서 자살 장면을 봤는데 저도 죽고싶어요"
        })
        void firstPersonOverridesContextMarker(String message) {
            assertConfirmedCrisis(message);
        }

        @Test
        @DisplayName("약한 회복 표현만으로는 강등하지 않는다")
        void weakRecoveryAloneDoesNotDowngrade() {
            assertConfirmedCrisis("지금은 정말 죽고싶어요");
        }

        /**
         * 마커 검출이 메시지 전체를 훑으면 앞 절의 무관한 3인칭 서술이 뒤 절의 1인칭 위기 진술을
         * 강등시킨다. 마커는 위기 진술과 같은 절에 있을 때만 유효해야 한다.
         */
        @ParameterizedTest(name = "다른 절의 마커: {0}")
        @ValueSource(strings = {
                "엄마가 오늘 늦는다고 해서 집에 혼자 있는데 나 진짜 죽고싶어",
                "친구가 어제 이사한다고 했는데 그건 그렇고 나 요즘 죽고싶어",
                "뉴스에서 경제 기사를 봤는데 그것과 별개로 저는 정말 죽고싶어요"
        })
        void markerInAnotherClauseDoesNotDowngrade(String message) {
            assertConfirmedCrisis(message);
        }

        /**
         * 위 케이스들은 모두 1인칭 주어를 포함하므로 1인칭 예외만으로도 통과한다.
         * 한국어는 주어 생략이 일상적이라, 절 스코프가 실제로 동작하는지는 <b>주어 없는</b>
         * 위기 진술로 확인해야 한다. 이 케이스가 강등되면 남은 방어선은 InputJudge 판정 하나뿐이다.
         */
        @ParameterizedTest(name = "주어 생략 + 다른 절의 마커: {0}")
        @ValueSource(strings = {
                "오늘 친구가 전화해서 화가 났는데 진짜 죽고싶다",
                "뉴스에서 경제 기사를 봤는데 그것과 별개로 정말 죽고싶어요",
                "동생이 시험 떨어졌다고 하더라고요 그건 그렇고 요즘 죽고싶어요",
                "영화 봤는데 재미없었어요 그냥 죽고싶다"
        })
        void implicitSubjectCrisisSurvivesDistantMarker(String message) {
            assertConfirmedCrisis(message);
        }

        /**
         * "절대"는 부정어가 아니라 강조어라서 절망을 강화하는 쪽으로도 쓰인다.
         * 부정 마커로 등록하면 강조된 위기 진술이 강등된다.
         */
        @ParameterizedTest(name = "강조어 오인: {0}")
        @ValueSource(strings = {
                "이제 절대 못 버티겠어 그냥 죽고싶다",
                "진짜 절대 못 견디겠어요 죽고싶어요"
        })
        void intensifierIsNotNegation(String message) {
            assertConfirmedCrisis(message);
        }

        /**
         * "예전엔 X 지금은 Y" 구문은 악화 서술에도 그대로 쓰인다.
         * 시점 대조어만으로 회복을 판정하면 진행 중인 위기를 과거 회복담으로 오인한다.
         */
        @ParameterizedTest(name = "시점 대조 ≠ 회복: {0}")
        @ValueSource(strings = {
                "예전엔 좋았는데 지금은 진짜 죽고싶어",
                "옛날에는 버틸만했는데 이제는 죽고싶어요"
        })
        void temporalContrastWithoutRecoveryStaysCrisis(String message) {
            assertConfirmedCrisis(message);
        }
    }

    @Nested
    @DisplayName("기존 동작 유지")
    class Unchanged {

        @Test
        @DisplayName("위기어 없는 일상 발화는 신호가 없다")
        void normalMessageHasNoSignal() {
            SafetyL1Result result = check("오늘 날씨 좋네요");
            assertThat(result.hardCrisis()).isFalse();
            assertThat(result.hardCrisisUnverified()).isFalse();
            assertThat(result.riskCandidate()).isFalse();
        }

        @Test
        @DisplayName("관용 과장은 위기어 목록에 없어 그대로 통과한다")
        void idiomaticExaggerationPasses() {
            SafetyL1Result result = check("과제 때문에 죽겠어요");
            assertThat(result.hardCrisis()).isFalse();
            assertThat(result.hardCrisisUnverified()).isFalse();
        }

        @Test
        @DisplayName("RISK 키워드는 기존대로 riskCandidate로 남는다")
        void riskKeywordUnaffected() {
            SafetyL1Result result = check("그냥 사라지고 싶다");
            assertThat(result.hardCrisis()).isFalse();
            assertThat(result.hardCrisisUnverified()).isFalse();
            assertThat(result.riskCandidate()).isTrue();
        }

        @Test
        @DisplayName("강등 시 signals에 근거가 남는다")
        void downgradeRecordsSignal() {
            SafetyL1Result result = check("친구가 죽고싶다고 해서 걱정돼요");
            assertThat(result.signals())
                    .anyMatch(signal -> signal.startsWith("crisis_context_marker:"));
        }
    }
}
