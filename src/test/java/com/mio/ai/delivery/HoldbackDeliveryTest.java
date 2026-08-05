package com.mio.ai.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 이슈 #306 — 승인된 단위만 사용자에게 열린다. */
class HoldbackDeliveryTest {

    private final List<String> sent = new ArrayList<>();

    private HoldbackDelivery delivery(HoldbackDelivery.UnitGate gate) {
        return new HoldbackDelivery(new ApprovedUnitBuffer(), gate, sent::add);
    }

    @Test
    @DisplayName("검사를 통과한 단위만 전달된다")
    void onlyApprovedUnitsAreSent() throws Exception {
        HoldbackDelivery holdback = delivery(candidate -> true);

        holdback.consume(List.of("괜찮아요.", " 지금 ", "어떤 기분이 드나요?"));

        assertThat(String.join("", sent)).isEqualTo("괜찮아요. 지금 어떤 기분이 드나요?");
        assertThat(holdback.blocked()).isFalse();
    }

    @Test
    @DisplayName("위반 단위는 전달되지 않고 그 뒤 청크도 열리지 않는다")
    void violatingUnitIsNeverSent() throws Exception {
        HoldbackDelivery holdback = delivery(candidate -> !candidate.contains("금지어"));

        holdback.consume(List.of("괜찮아요. ", "금지어가 들어간 문장이에요. ", "그 뒤 문장입니다."));

        assertThat(String.join("", sent))
                .as("검사를 통과하지 않은 문자는 사용자에게 도달하면 안 된다")
                .isEqualTo("괜찮아요. ");
        assertThat(String.join("", sent)).doesNotContain("금지어");
        assertThat(holdback.blocked()).isTrue();
    }

    @Test
    @DisplayName("첫 단위가 위반이면 아무것도 전달되지 않는다")
    void firstUnitViolationSendsNothing() throws Exception {
        HoldbackDelivery holdback = delivery(candidate -> !candidate.contains("금지어"));

        holdback.consume(List.of("금지어로 시작하는 문장이에요. ", "뒤 문장."));

        assertThat(sent)
                .as("이전 구현은 첫 검사 전 최대 200자를 이미 내보냈다")
                .isEmpty();
        assertThat(holdback.firstSubstantiveTokenMs()).isEqualTo(-1);
    }

    @Test
    @DisplayName("종결 부호 없이 끝나도 마지막 단위를 검사하고 전달한다")
    void trailingTextWithoutTerminatorIsStillDelivered() throws Exception {
        HoldbackDelivery holdback = delivery(candidate -> true);

        holdback.consume(List.of("마침표가 없는 마지막 문장"));

        assertThat(String.join("", sent)).isEqualTo("마침표가 없는 마지막 문장");
    }

    @Test
    @DisplayName("검사는 누적 응답 전체로 한다 — 단위 하나만 보면 문맥이 끊긴다")
    void gateReceivesAccumulatedContent() throws Exception {
        List<String> candidates = new ArrayList<>();
        HoldbackDelivery holdback = new HoldbackDelivery(
                new ApprovedUnitBuffer(),
                candidate -> {
                    candidates.add(candidate);
                    return true;
                },
                sent::add);

        holdback.consume(List.of("첫 문장. ", "둘째 문장."));

        assertThat(candidates).containsExactly("첫 문장. ", "첫 문장. 둘째 문장.");
    }

    @Test
    @DisplayName("전달 시각은 첫 승인 단위 기준으로 기록된다")
    void firstSubstantiveTokenIsMeasuredAtFirstApproval() throws Exception {
        List<Long> clock = new ArrayList<>(List.of(1_000L, 1_050L, 1_100L, 1_200L));
        int[] index = {0};
        HoldbackDelivery holdback = new HoldbackDelivery(
                new ApprovedUnitBuffer(),
                candidate -> true,
                sent::add,
                () -> clock.get(Math.min(index[0]++, clock.size() - 1)));

        holdback.consume(List.of("첫 문장. ", "둘째 문장."));

        assertThat(holdback.firstSubstantiveTokenMs())
                .as("첫 생성 토큰이 아니라 승인되어 실제 전달된 첫 콘텐츠 기준이다")
                .isEqualTo(50L);
    }

    @Test
    @DisplayName("검사를 통과하지 않은 채 전달된 문자는 0이다")
    void noUnverifiedExposure() throws Exception {
        HoldbackDelivery holdback = delivery(candidate -> !candidate.contains("금지어"));

        holdback.consume(List.of("안전한 문장. ", "금지어 포함 문장."));

        assertThat(holdback.unverifiedExposedChars()).isZero();
    }
}
