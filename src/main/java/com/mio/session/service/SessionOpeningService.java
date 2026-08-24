package com.mio.session.service;

import com.mio.character.domain.OpeningMessageCatalog;
import com.mio.character.domain.OpeningMessageCatalog.OpeningAudience;
import com.mio.character.domain.OpeningMessageCatalog.OpeningMessage;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.session.domain.Message;
import com.mio.session.domain.MessageKind;
import com.mio.session.domain.MessageRole;
import com.mio.session.domain.Session;
import com.mio.session.dto.InitialAssistantMessageResponse;
import com.mio.session.repository.MessageRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.user.domain.User;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 세션 선제 인사 저장·조회 (이슈 #530, 원본 추적 #428).
 *
 * <p>인사는 화면 표시와 대화 이력이 갈라지지 않도록 일반 메시지와 동일하게 암호화 저장한다.
 * 표시 전용으로 두면 서버는 인사를 모르는데 사용자 화면에는 있는 이중 상태가 된다.
 *
 * <p>{@link SessionService} 에서 분리한 이유는 세션 생성 메서드가 인사 선택·저장·복호화까지
 * 안게 되면 한 메서드가 여러 책임을 갖게 되기 때문이다. 트랜잭션은 호출자의 것을 그대로
 * 쓴다 — 세션만 있고 인사가 없는 중간 상태를 만들지 않기 위해 같은 트랜잭션이어야 한다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SessionOpeningService {

    /** 오프닝 생성 결과. 카피 누락·캐릭터별 분포 감시. */
    private static final String CREATED_METRIC = "mio.session.opening.created";

    /** 직전과 같은 문구가 나간 횟수. 0 에 수렴해야 로테이션이 동작하는 것이다. */
    private static final String REPEAT_METRIC = "mio.session.opening.variant.repeat";

    /** 직전 variant 조회 실패 — fail-open 경로를 탄 빈도. */
    private static final String LOOKUP_FAILED_METRIC = "mio.session.opening.variant.lookup_failed";

    /** 재방문 판정 조회 실패 — 자기소개로 폴백한 빈도. */
    private static final String AUDIENCE_LOOKUP_FAILED_METRIC = "mio.session.opening.audience.lookup_failed";

    /** 가입 계약({@code SignupCompleteRequest})과 같은 닉네임 길이 범위. */
    private static final int NICKNAME_MIN_LENGTH = 2;
    private static final int NICKNAME_MAX_LENGTH = 13;

    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final MessageEncryptor messageEncryptor;
    private final MeterRegistry meterRegistry;

    /**
     * 세션의 선제 인사를 저장하고 응답 DTO 를 만든다.
     *
     * <p>호출자(세션 생성)의 트랜잭션 안에서 실행된다. 세션당 1건은
     * {@code uq_messages_session_opening} 이 최종 보장한다.
     */
    public InitialAssistantMessageResponse createOpening(Session session, User user) {
        OpeningAudience audience = resolveAudience(session, user);
        OpeningMessage selected = select(user.getId(), session.getCharacterId(), audience);
        String rendered = selected.render(usableNickname(user));

        byte[] ciphertext = messageEncryptor.encrypt(rendered.getBytes(StandardCharsets.UTF_8));
        Message saved = messageRepository.save(Message.builder()
                .session(session)
                .user(user)
                .role(MessageRole.ASSISTANT)
                .messageKind(MessageKind.SESSION_OPENING)
                .openingVariant(selected.variant())
                .contentCiphertext(ciphertext)
                .contentDekId(messageEncryptor.dekId())
                .isCrisisFlagged(false)
                .build());

        meterRegistry.counter(CREATED_METRIC,
                "character", session.getCharacterId(),
                "audience", audience.name().toLowerCase(),
                "variant", selected.variant()).increment();

        return InitialAssistantMessageResponse.from(saved, rendered);
    }

    /**
     * 자기소개를 할지 닉네임을 부를지 정한다 (이슈 #530).
     *
     * <p>재방문 판정은 <b>이 사용자의 다른 세션 존재 여부</b>다. 오프닝 이력으로 판정하면 이
     * 기능 배포 이전부터 쓰던 사용자가 모두 "처음 온 사람" 이 되어 자기소개를 다시 받는다.
     *
     * <p>닉네임이 없거나 형식이 어긋나면 재방문이어도 자기소개 세트를 쓴다. 그 세트는 이름을
     * 부르지 않으므로 빈 이름이 문장에 노출될 경로가 없다.
     */
    private OpeningAudience resolveAudience(Session session, User user) {
        if (usableNickname(user) == null) {
            return OpeningAudience.FIRST_SESSION;
        }
        return hasPreviousSession(session, user)
                ? OpeningAudience.RETURNING
                : OpeningAudience.FIRST_SESSION;
    }

    /**
     * 방금 만든 세션을 제외한 이전 세션이 있는지. 조회가 실패하면 첫 세션으로 본다 —
     * 처음 온 사람에게 자기소개를 하는 쪽이, 처음 온 사람의 이름을 부르는 쪽보다 안전하다.
     */
    private boolean hasPreviousSession(Session session, User user) {
        try {
            return sessionRepository.existsByUser_IdAndIdNot(user.getId(), session.getId());
        } catch (Exception e) {
            meterRegistry.counter(AUDIENCE_LOOKUP_FAILED_METRIC).increment();
            log.warn("Failed to check previous sessions, treating as first session: userId={}",
                    user.getId(), e);
            return false;
        }
    }

    /**
     * 인사말에 넣을 수 있는 닉네임. 없으면 {@code null}.
     *
     * <p>가입 계약(2~13자)을 벗어난 값은 쓰지 않는다. 한 글자나 비정상적으로 긴 값이
     * 문장에 들어가면 인사가 깨진 것처럼 보인다.
     */
    private String usableNickname(User user) {
        String nickname = user.getNickname();
        if (nickname == null || nickname.isBlank()) {
            return null;
        }
        String trimmed = nickname.trim();
        if (trimmed.length() < NICKNAME_MIN_LENGTH || trimmed.length() > NICKNAME_MAX_LENGTH) {
            return null;
        }
        return trimmed;
    }

    /**
     * 저장된 선제 인사를 복구한다 (재진입·응답 유실 후 active 조회).
     *
     * <p>새로 만들지 않는다. 없으면 {@code Optional.empty()} — 이 기능 배포 이전에 이미 열려
     * 있던 세션에는 인사가 존재하지 않고, 대화가 진행된 세션 중간에 소급 생성하면
     * {@code created_at} 순서가 첫 사용자 발화보다 뒤로 가서 이력이 어긋난다.
     *
     * <p>복호화가 실패해도 예외를 전파하지 않는다. 인사 한 줄 때문에 활성 세션 조회 전체가
     * 실패하면 사용자는 진행 중인 대화로 돌아갈 수 없다.
     */
    @Transactional(readOnly = true)
    public Optional<InitialAssistantMessageResponse> findOpening(UUID sessionId) {
        return messageRepository.findBySession_IdAndMessageKind(sessionId, MessageKind.SESSION_OPENING)
                .flatMap(message -> {
                    try {
                        String plainText = new String(
                                messageEncryptor.decrypt(message.getContentCiphertext()),
                                StandardCharsets.UTF_8);
                        return Optional.of(InitialAssistantMessageResponse.from(message, plainText));
                    } catch (Exception e) {
                        log.warn("Failed to decrypt session opening, omitting it: sessionId={}", sessionId, e);
                        return Optional.empty();
                    }
                });
    }

    /**
     * 직전에 쓴 문구를 뺀 뒤 균등 무작위로 고른다.
     *
     * <p>사용자·세션 ID 해시로 고르지 않는 이유: 같은 사용자가 매번 같은 문구를 받게 되어
     * 로테이션의 목적을 잃는다.
     */
    private OpeningMessage select(UUID userId, String characterId, OpeningAudience audience) {
        List<OpeningMessage> candidates = OpeningMessageCatalog.messagesFor(characterId, audience);
        String previousVariant = findPreviousVariant(userId);

        List<OpeningMessage> pool = new ArrayList<>(candidates);
        if (previousVariant != null && pool.size() > 1) {
            pool.removeIf(message -> message.variant().equals(previousVariant));
        }

        OpeningMessage selected = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        if (selected.variant().equals(previousVariant)) {
            // 후보가 1종뿐인 캐릭터에서만 발생한다. 로테이션이 실제로 도는지 보려면
            // "직전과 같았다" 를 세는 지표가 필요하다.
            meterRegistry.counter(REPEAT_METRIC, "character", characterId).increment();
        }
        return selected;
    }

    /**
     * 직전 오프닝 variant. 없거나 조회가 실패하면 {@code null} 을 반환한다 (fail-open).
     *
     * <p>인사 문구를 고르는 보조 조회가 세션 생성을 막아서는 안 된다. 최악의 경우 같은
     * 문구가 한 번 더 나오는 것이고, 그건 인사가 아예 없는 것보다 낫다.
     */
    private String findPreviousVariant(UUID userId) {
        try {
            List<String> recent = messageRepository.findRecentOpeningVariants(
                    userId, MessageKind.SESSION_OPENING, PageRequest.of(0, 1));
            return recent.isEmpty() ? null : recent.get(0);
        } catch (Exception e) {
            meterRegistry.counter(LOOKUP_FAILED_METRIC).increment();
            log.warn("Failed to load previous opening variant, selecting from full set: userId={}", userId, e);
            return null;
        }
    }
}
