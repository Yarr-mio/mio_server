package com.mio.character.domain;

import com.mio.character.domain.OpeningMessageCatalog.OpeningAudience;
import com.mio.character.domain.OpeningMessageCatalog.OpeningMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 선제 인사 문구 계약 (이슈 #530).
 *
 * <p>문구는 사용자에게 그대로 노출되는 제품 카피이고 LLM 검증을 거치지 않는다. 그래서
 * 안전 계약(진단·단정·의존성 강화 금지)과 닉네임 슬롯 규칙을 코드에서 고정한다.
 */
class OpeningMessageCatalogTest {

    private static final Pattern VARIANT = Pattern.compile("^[a-z]+_(intro|back)_\\d{2}$");

    /** 로테이션을 체감하려면 모드별로 최소 2종이 필요하다. 확정 세트는 3종이다. */
    private static final int EXPECTED_VARIANTS_PER_SET = 3;

    /** 가입 계약 상한(13자)을 채운 닉네임. 렌더링 길이 검증에 쓴다. */
    private static final String LONGEST_NICKNAME = "가나다라마바사아자차카타파";

    private static final List<String> CHARACTER_IDS = List.of("mio", "bau", "rumi", "momo", "chichi");

    /**
     * 캐릭터 변경 API 가 반환하는 대표 문구. 세트의 첫 자기소개 문구와 같아야 한다.
     * "나 X야" → "난 X야" 어조 정정을 포함한다.
     */
    private static final List<String> REPRESENTATIVE_MESSAGES = List.of(
            "안녕! 난 미오야 🐧 오늘 어떤 하루를 보냈어?",
            "안녕! 난 바우야 🐕 오늘 뭘 해봤어?",
            "안녕, 난 루미야 🦉 무엇이 너를 괴롭히고 있어?",
            "안녕... 난 모모야 🐻 오늘 어떤 하루였어?",
            "안녕, 난 치치야 😺 뭐가 문제야, 말해봐."
    );

    static List<String> characterIds() {
        return CHARACTER_IDS;
    }

    @Test
    @DisplayName("캐릭터 5종이 모두 카탈로그에 있다")
    void catalog_coversAllCharacters() {
        assertThat(OpeningMessageCatalog.characterIds()).containsExactlyElementsOf(CHARACTER_IDS);
    }

    @ParameterizedTest
    @MethodSource("characterIds")
    @DisplayName("캐릭터별로 모드마다 3종씩 있고 모두 비어 있지 않다")
    void messagesFor_hasThreeNonBlankVariantsPerAudience(String characterId) {
        for (OpeningAudience audience : OpeningAudience.values()) {
            List<OpeningMessage> messages = OpeningMessageCatalog.messagesFor(characterId, audience);

            assertThat(messages).hasSize(EXPECTED_VARIANTS_PER_SET);
            assertThat(messages).allSatisfy(message -> {
                assertThat(message.content()).isNotBlank();
                assertThat(message.variant()).matches(VARIANT);
                assertThat(message.variant()).startsWith(characterId + "_");
            });
        }
    }

    @Test
    @DisplayName("첫 세션 문구는 닉네임 슬롯을 쓰지 않는다 — 닉네임이 없어도 안전해야 한다")
    void firstSessionMessages_haveNoNicknameSlot() {
        assertThat(messagesOf(OpeningAudience.FIRST_SESSION)).allSatisfy(message -> {
            assertThat(message.requiresNickname()).isFalse();
            assertThat(message.render(null)).isEqualTo(message.content());
        });
    }

    @Test
    @DisplayName("재방문 문구는 모두 닉네임 슬롯을 정확히 1개 가진다")
    void returningMessages_haveExactlyOneNicknameSlot() {
        assertThat(messagesOf(OpeningAudience.RETURNING)).allSatisfy(message -> {
            assertThat(message.requiresNickname()).isTrue();
            assertThat(occurrences(message.content(), OpeningMessageCatalog.NICKNAME_PLACEHOLDER))
                    .as("%s 에 슬롯이 2개 이상이면 이름이 중복 노출된다", message.variant())
                    .isEqualTo(1);
        });
    }

    @Test
    @DisplayName("재방문 문구는 닉네임을 넣으면 슬롯이 남지 않는다")
    void returningMessages_renderWithoutLeftoverSlot() {
        assertThat(messagesOf(OpeningAudience.RETURNING)).allSatisfy(message -> {
            String rendered = message.render("민석");
            assertThat(rendered).contains("민석");
            assertThat(rendered).doesNotContain("{", "}");
        });
    }

    @Test
    @DisplayName("닉네임 없이 재방문 문구를 렌더링하면 예외 — 슬롯이 그대로 노출되지 않는다")
    void returningMessage_renderWithoutNickname_throws() {
        OpeningMessage message = OpeningMessageCatalog
                .messagesFor("mio", OpeningAudience.RETURNING).get(0);

        assertThatThrownBy(() -> message.render(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message.variant());
    }

    @Test
    @DisplayName("variant 코드는 전체에서 유일하다")
    void variants_areGloballyUnique() {
        List<String> variants = allMessages().stream().map(OpeningMessage::variant).toList();

        assertThat(Set.copyOf(variants))
                .as("variant 가 겹치면 직전 문구 제외가 다른 문구까지 지운다")
                .hasSameSizeAs(variants);
    }

    @ParameterizedTest
    @MethodSource("characterIds")
    @DisplayName("같은 캐릭터·모드 안에서 문구 본문이 중복되지 않는다")
    void contents_areDistinctWithinSet(String characterId) {
        for (OpeningAudience audience : OpeningAudience.values()) {
            List<String> contents = OpeningMessageCatalog.messagesFor(characterId, audience).stream()
                    .map(OpeningMessage::content)
                    .toList();

            assertThat(Set.copyOf(contents))
                    .as("%s/%s 문구가 중복되면 로테이션이 무의미하다", characterId, audience)
                    .hasSameSizeAs(contents);
        }
    }

    @Test
    @DisplayName("문구는 질문을 최대 1개만 포함한다")
    void contents_haveAtMostOneQuestion() {
        assertThat(allMessages()).allSatisfy(message ->
                assertThat(countQuestionMarks(message.content()))
                        .as("%s 문구에 질문이 2개 이상이다: %s", message.variant(), message.content())
                        .isLessThanOrEqualTo(1));
    }

    @Test
    @DisplayName("가장 긴 닉네임을 넣어도 문구 길이가 상한을 넘지 않는다")
    void contents_areShortEvenWithLongestNickname() {
        assertThat(allMessages()).allSatisfy(message -> {
            String rendered = message.requiresNickname()
                    ? message.render(LONGEST_NICKNAME)
                    : message.content();
            assertThat(rendered.length())
                    .as("%s 문구가 너무 길다: %s", message.variant(), rendered)
                    .isLessThanOrEqualTo(60);
        });
    }

    @Test
    @DisplayName("금지 표현이 없다 — 진단·위기 단정·의존성 강화·과제 지시")
    void contents_avoidForbiddenExpressions() {
        List<String> forbidden = List.of(
                "우울증", "불안장애", "진단", "장애", "치료",
                "항상 곁에", "언제나 곁에", "나만 믿어", "나밖에",
                "죽", "자살", "위기",
                "숙제", "과제", "미션", "해야 해", "해야 돼"
        );

        assertThat(allMessages()).allSatisfy(message ->
                assertThat(forbidden)
                        .as("%s 문구에 금지 표현이 있다: %s", message.variant(), message.content())
                        .noneSatisfy(word -> assertThat(message.content()).contains(word)));
    }

    @Test
    @DisplayName("자기소개 문구는 캐릭터 이름을 밝힌다")
    void firstSessionMessages_introduceCharacterName() {
        List<String> names = List.of("미오", "바우", "루미", "모모", "치치");

        for (int i = 0; i < CHARACTER_IDS.size(); i++) {
            String name = names.get(i);
            assertThat(OpeningMessageCatalog.messagesFor(CHARACTER_IDS.get(i), OpeningAudience.FIRST_SESSION))
                    .as("첫 세션인데 자기소개가 없으면 사용자는 상대가 누구인지 모른다")
                    .allSatisfy(message -> assertThat(message.content()).contains("난 " + name + "야"));
        }
    }

    @Test
    @DisplayName("재방문 문구는 캐릭터 자기소개를 반복하지 않는다")
    void returningMessages_doNotReintroduce() {
        List<String> introductions = List.of("난 미오야", "난 바우야", "난 루미야", "난 모모야", "난 치치야");

        assertThat(messagesOf(OpeningAudience.RETURNING)).allSatisfy(message ->
                assertThat(introductions)
                        .as("%s 가 자기소개를 반복한다: %s", message.variant(), message.content())
                        .noneSatisfy(intro -> assertThat(message.content()).contains(intro)));
    }

    @Test
    @DisplayName("대표 문구는 자기소개 세트의 첫 문구다")
    void representativeMessage_isFirstIntroduction() {
        List<String> representatives = CHARACTER_IDS.stream()
                .map(OpeningMessageCatalog::representativeMessage)
                .toList();

        assertThat(representatives).containsExactlyElementsOf(REPRESENTATIVE_MESSAGES);
        assertThat(representatives)
                .as("캐릭터 변경 응답은 닉네임을 다루지 않으므로 슬롯이 없어야 한다")
                .allSatisfy(message -> assertThat(message).doesNotContain("{"));
    }

    @Test
    @DisplayName("알 수 없는 캐릭터는 mio 세트로 폴백한다")
    void messagesFor_unknownCharacter_fallsBackToMio() {
        for (OpeningAudience audience : OpeningAudience.values()) {
            assertThat(OpeningMessageCatalog.messagesFor("unknown-character", audience))
                    .as("카피 누락 하나가 세션 생성 전체를 막지 않아야 한다")
                    .isEqualTo(OpeningMessageCatalog.messagesFor(
                            OpeningMessageCatalog.FALLBACK_CHARACTER_ID, audience));
        }
    }

    private static List<OpeningMessage> messagesOf(OpeningAudience audience) {
        List<OpeningMessage> messages = new ArrayList<>();
        CHARACTER_IDS.forEach(id -> messages.addAll(OpeningMessageCatalog.messagesFor(id, audience)));
        return messages;
    }

    private static List<OpeningMessage> allMessages() {
        List<OpeningMessage> messages = new ArrayList<>();
        for (OpeningAudience audience : OpeningAudience.values()) {
            messages.addAll(messagesOf(audience));
        }
        return messages;
    }

    private static long countQuestionMarks(String content) {
        return content.chars().filter(c -> c == '?' || c == '？').count();
    }

    private static int occurrences(String content, String token) {
        int count = 0;
        int index = content.indexOf(token);
        while (index >= 0) {
            count++;
            index = content.indexOf(token, index + token.length());
        }
        return count;
    }
}
