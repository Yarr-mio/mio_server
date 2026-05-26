package com.mio.character.service;

import com.mio.character.dto.CharacterChangeRequest;
import com.mio.character.dto.CharacterChangeResponse;
import com.mio.character.dto.CharacterListResponse;
import com.mio.character.dto.UserCharacterResponse;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterServiceTest {

    @Mock
    private UserRepository userRepository;

    private CharacterService characterService;
    private UUID userId;

    @BeforeEach
    void setUp() {
        characterService = new CharacterService(userRepository);
        userId = UUID.randomUUID();
    }

    @Test
    @DisplayName("listCharacters는 5개 캐릭터를 반환한다")
    void listCharacters_returnsFiveCharacters() {
        CharacterListResponse response = characterService.listCharacters();
        assertThat(response.characters()).hasSize(5);
    }

    @Test
    @DisplayName("listCharacters는 mio를 포함한다")
    void listCharacters_containsMio() {
        CharacterListResponse response = characterService.listCharacters();
        assertThat(response.characters())
                .anyMatch(c -> "mio".equals(c.characterId()));
    }

    @Test
    @DisplayName("changeCharacter는 온보딩 완료 사용자의 캐릭터를 변경한다")
    void changeCharacter_onboardingCompleted_updatesCharacter() {
        User user = User.builder()
                .socialProvider("kakao")
                .socialId("test")
                .privacyConsent(true)
                .build();
        user.completeOnboarding("mio");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        CharacterChangeResponse response = characterService.changeCharacter(
                userId, new CharacterChangeRequest("bau"));

        assertThat(response.characterId()).isEqualTo("bau");
        assertThat(response.changed()).isTrue();
        assertThat(response.greetingMessage()).isNotBlank();
    }

    @Test
    @DisplayName("changeCharacter는 온보딩 미완료 사용자에게 ONBOARDING_REQUIRED를 던진다")
    void changeCharacter_onboardingNotCompleted_throwsOnboardingRequired() {
        User user = User.builder()
                .socialProvider("kakao")
                .socialId("test")
                .privacyConsent(true)
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> characterService.changeCharacter(
                userId, new CharacterChangeRequest("bau")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ONBOARDING_REQUIRED);
    }

    @Test
    @DisplayName("changeCharacter는 유효하지 않은 캐릭터 ID에 INVALID_CHARACTER_ID를 던진다")
    void changeCharacter_invalidCharacterId_throwsInvalidCharacterId() {
        assertThatThrownBy(() -> characterService.changeCharacter(
                userId, new CharacterChangeRequest("invalid")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CHARACTER_ID);
    }

    @Test
    @DisplayName("getCurrentCharacter는 현재 선택된 캐릭터 정보를 반환한다")
    void getCurrentCharacter_returnsCurrent() {
        User user = User.builder()
                .socialProvider("kakao")
                .socialId("test")
                .privacyConsent(true)
                .build();
        user.completeOnboarding("rumi");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserCharacterResponse response = characterService.getCurrentCharacter(userId);

        assertThat(response.characterId()).isEqualTo("rumi");
        assertThat(response.name()).isEqualTo("루미");
        assertThat(response.thumbnailUrl()).contains("rumi");
    }
}
