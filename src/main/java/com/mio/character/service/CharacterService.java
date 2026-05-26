package com.mio.character.service;

import com.mio.character.dto.*;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CharacterService {

    private static final Set<String> VALID_IDS = Set.of("mio", "bau", "rumi", "momo", "chichi");

    private static final List<CharacterDto> CATALOG = List.of(
            new CharacterDto("mio", "미오", "penguin",
                    "따뜻하고 섬세하게 감정을 들어줘요.",
                    List.of("warm", "empathetic", "gentle"),
                    "https://cdn.mio.app/characters/mio/thumbnail.png"),
            new CharacterDto("bau", "바우", "dog",
                    "활동적인 변화로 함께 나아가요.",
                    List.of("active", "supportive", "motivated"),
                    "https://cdn.mio.app/characters/bau/thumbnail.png"),
            new CharacterDto("rumi", "루미", "owl",
                    "명확한 사고로 복잡한 감정을 정리해요.",
                    List.of("analytical", "wise", "logical"),
                    "https://cdn.mio.app/characters/rumi/thumbnail.png"),
            new CharacterDto("momo", "모모", "bear",
                    "지치고 힘든 마음을 따뜻하게 감싸드려요.",
                    List.of("gentle", "accepting", "nurturing"),
                    "https://cdn.mio.app/characters/momo/thumbnail.png"),
            new CharacterDto("chichi", "치치", "cat",
                    "현실적인 해결책으로 변화를 이끌어요.",
                    List.of("practical", "direct", "solution-focused"),
                    "https://cdn.mio.app/characters/chichi/thumbnail.png")
    );

    private static final Map<String, String> GREETINGS = Map.of(
            "mio", "안녕! 나 미오야 🐧 오늘 어떤 하루를 보냈어?",
            "bau", "안녕! 나 바우야 🐕 오늘 뭘 해봤어?",
            "rumi", "안녕, 나 루미야 🦉 무엇이 너를 괴롭히고 있어?",
            "momo", "안녕... 나 모모야 🐻 오늘 어떤 하루였어?",
            "chichi", "안녕, 치치야 😺 뭐가 문제야, 말해봐."
    );

    private final UserRepository userRepository;

    public CharacterListResponse listCharacters() {
        return new CharacterListResponse(CATALOG);
    }

    @Transactional
    public CharacterChangeResponse changeCharacter(UUID userId, CharacterChangeRequest request) {
        if (!VALID_IDS.contains(request.characterId())) {
            throw new BusinessException(ErrorCode.INVALID_CHARACTER_ID);
        }

        User user = findUser(userId);
        String step = user.getSignupStep();
        if (!"ONBOARDING_COMPLETED".equals(step) && !"COMPLETED".equals(step)) {
            throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
        }

        user.changeCharacter(request.characterId());
        return new CharacterChangeResponse(
                request.characterId(),
                true,
                GREETINGS.get(request.characterId())
        );
    }

    @Transactional(readOnly = true)
    public UserCharacterResponse getCurrentCharacter(UUID userId) {
        User user = findUser(userId);
        String characterId = user.getPreferredCharacterId();
        CharacterDto info = CATALOG.stream()
                .filter(c -> c.characterId().equals(characterId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return new UserCharacterResponse(info.characterId(), info.name(), info.thumbnailUrl());
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
