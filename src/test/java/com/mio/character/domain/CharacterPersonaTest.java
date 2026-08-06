package com.mio.character.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CharacterPersonaTest {

    @ParameterizedTest
    @EnumSource(CharacterPersona.class)
    @DisplayName("모든 캐릭터가 대화 프롬프트와 요약 어조를 모두 정의한다")
    void everyPersonaDefinesBothVoices(CharacterPersona persona) {
        assertThat(persona.characterId()).isNotBlank();
        assertThat(persona.displayName()).isNotBlank();
        assertThat(persona.chatSystemPrompt()).contains("당신은 " + persona.displayName() + "입니다");
        assertThat(persona.summaryVoice()).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(CharacterPersona.class)
    @DisplayName("요약 어조에는 대화 턴 전용 규칙이 섞이지 않는다")
    void summaryVoiceHasNoChatTurnRules(CharacterPersona persona) {
        // "응답은 2-4문장" 같은 대화 턴 규칙이 요약 어조에 새면 요약 길이 계약과 충돌한다.
        assertThat(persona.summaryVoice()).doesNotContain("응답은");
    }

    @Test
    @DisplayName("id로 캐릭터를 찾고, 대소문자·공백은 정규화한다")
    void findNormalizesInput() {
        assertThat(CharacterPersona.find("chichi")).contains(CharacterPersona.CHICHI);
        assertThat(CharacterPersona.find("  CHICHI  ")).contains(CharacterPersona.CHICHI);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "unknown", "MIO2"})
    @DisplayName("알 수 없는 id는 비어 있는 Optional, findOrDefault는 기본 캐릭터")
    void unknownIdFallsBackToDefault(String characterId) {
        assertThat(CharacterPersona.find(characterId)).isEmpty();
        assertThat(CharacterPersona.findOrDefault(characterId)).isEqualTo(CharacterPersona.DEFAULT);
    }

    @Test
    @DisplayName("validIds는 정의된 캐릭터 전체를 반환한다")
    void validIdsCoversAllPersonas() {
        assertThat(CharacterPersona.validIds())
                .containsExactlyInAnyOrder("mio", "bau", "rumi", "momo", "chichi");
    }
}
