package com.mio.character.service;

import com.mio.character.dto.*;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.user.domain.User;
import com.mio.user.domain.SignupStep;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CharacterService {

    private static final Set<String> VALID_IDS = Set.of("mio", "bau", "rumi", "momo", "chichi");

    // 내부 카탈로그 (character_id → CharacterDto)
    private static final Map<String, CharacterDto> CATALOG_MAP;
    private static final List<String> CATALOG_ORDER = List.of("mio", "bau", "rumi", "momo", "chichi");

    static {
        Map<String, CharacterDto> map = new LinkedHashMap<>();
        map.put("mio",   new CharacterDto("mio",   "미오", "펭귄",   "따뜻하고 언제나 곁에 있어주는 파트너",           List.of("warm", "empathetic", "gentle"),         List.of("공감형", "따뜻함")));
        map.put("bau",   new CharacterDto("bau",   "바우", "강아지", "활동적인 변화로 함께 나아가요.",                  List.of("active", "supportive", "motivated"),    List.of("활기참", "긍정적")));
        map.put("rumi",  new CharacterDto("rumi",  "루미", "부엉이", "명확한 사고로 복잡한 감정을 정리해요.",           List.of("analytical", "wise", "logical"),        List.of("공감형", "논리적")));
        map.put("momo",  new CharacterDto("momo",  "모모", "곰",     "지치고 힘든 마음을 따뜻하게 감싸드려요.",        List.of("gentle", "accepting", "nurturing"),     List.of("차분함", "분석적")));
        map.put("chichi",new CharacterDto("chichi","치치", "고양이", "현실적인 해결책으로 변화를 이끌어요.",            List.of("practical", "direct", "solution-focused"), List.of("독립적", "감각적")));
        CATALOG_MAP = Collections.unmodifiableMap(map);
    }

    private static final Map<String, String> GREETINGS = Map.of(
            "mio",   "안녕! 나 미오야 🐧 오늘 어떤 하루를 보냈어?",
            "bau",   "안녕! 나 바우야 🐕 오늘 뭘 해봤어?",
            "rumi",  "안녕, 나 루미야 🦉 무엇이 너를 괴롭히고 있어?",
            "momo",  "안녕... 나 모모야 🐻 오늘 어떤 하루였어?",
            "chichi","안녕, 치치야 😺 뭐가 문제야, 말해봐."
    );

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public CharacterListResponse listCharacters(UUID userId) {
        User user = findUser(userId);
        String currentId = user.getPreferredCharacterId();

        List<CharacterItemDto> items = CATALOG_ORDER.stream()
                .map(id -> {
                    CharacterDto c = CATALOG_MAP.get(id);
                    return new CharacterItemDto(c.characterId(), c.name(), c.animal(), c.description(), c.tags(), id.equals(currentId));
                })
                .toList();

        return new CharacterListResponse(currentId, items);
    }

    @Transactional
    public CharacterChangeResponse changeCharacter(UUID userId, CharacterChangeRequest request) {
        if (!VALID_IDS.contains(request.characterId())) {
            throw new BusinessException(ErrorCode.INVALID_CHARACTER_ID);
        }

        User user = findUser(userId);
        if (!user.getSignupStep().isOnboardingComplete()) {
            throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
        }

        boolean changed = !request.characterId().equals(user.getPreferredCharacterId());
        user.changeCharacter(request.characterId());

        CharacterDto c = CATALOG_MAP.get(request.characterId());
        return new CharacterChangeResponse(
                request.characterId(),
                c.name(),
                changed,
                GREETINGS.get(request.characterId())
        );
    }

    @Transactional(readOnly = true)
    public UserCharacterResponse getCurrentCharacter(UUID userId) {
        User user = findUser(userId);
        CharacterDto c = CATALOG_MAP.get(user.getPreferredCharacterId());
        if (c == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return new UserCharacterResponse(c.characterId(), c.name(), c.animal());
    }

    public CharacterDto getCharacterInfo(String characterId) {
        return CATALOG_MAP.get(characterId);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
