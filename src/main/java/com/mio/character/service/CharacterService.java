package com.mio.character.service;

import com.mio.character.domain.CharacterPersona;
import com.mio.character.domain.OpeningMessageCatalog;
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

    // 유효 id 는 CharacterPersona 를 단일 출처로 삼는다 (이슈 #339). 캐릭터를 추가할 때
    // 어조 정의와 허용 목록이 따로 놀지 않게 한다. 아래 카탈로그의 사용자 노출 문구
    // (설명·태그·인사말)는 표현 층이 아니라 제품 카피라 여기 남겨 둔다.
    private static final Set<String> VALID_IDS = CharacterPersona.validIds();

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

    // 인사 카피는 OpeningMessageCatalog 가 단일 출처다 (이슈 #530). 세션 선제 인사와 같은
    // 문구를 쓰기 위해 이관했고, 이 엔드포인트는 계속 대표 문구 1종만 고정 반환한다 —
    // 로테이션을 여기 적용하면 기존 API 의 동작이 바뀐다.

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
                OpeningMessageCatalog.representativeMessage(request.characterId())
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
