package com.mio.session.service;

import com.mio.ai.input.InputNormalizer;
import com.mio.ai.safety.SafetyL1HistoryMessage;
import com.mio.ai.safety.UserMessageSignal;
import com.mio.ai.safety.UserMessageSignalAnalyzer;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.Message;
import com.mio.session.domain.MessageRole;
import com.mio.session.domain.MessageTurn;
import com.mio.session.domain.Session;
import com.mio.session.domain.TurnStatus;
import com.mio.session.repository.MessageRepository;
import com.mio.session.repository.MessageTurnRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionMessagePersistenceService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final MessageTurnRepository messageTurnRepository;
    private final MessageEncryptor messageEncryptor;
    private final InputNormalizer inputNormalizer;
    private final UserMessageSignalAnalyzer userMessageSignalAnalyzer;

    /**
     * 사용자 발화를 저장하고 턴을 연다 (이슈 P0-A).
     *
     * <p>응답 생성 <b>전에</b> 호출한다. 이전에는 스트리밍이 끝난 뒤 사용자·AI 메시지를 한 번에
     * 저장해서, LLM 실패나 SSE 타임아웃으로 그 지점에 도달하지 못하면 사용자가 입력한 발화까지
     * 사라졌다.
     *
     * <p>호출자의 트랜잭션에 참여하지 않는다. 이 저장은 이후 파이프라인이 실패하더라도 남아야
     * 하므로 독립 트랜잭션으로 커밋한다.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException
     *         같은 {@code (userId, idempotencyKey)} 턴이 이미 있을 때. 호출자가
     *         {@link #findTurn} 으로 기존 턴을 조회해 재생·대기를 판단한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MessageTurn openTurn(
            UUID sessionId,
            UUID userId,
            String userContent,
            UserMessageSignal userSignal,
            String idempotencyKey) {

        // 같은 키의 턴이 이미 있으면 새로 만들지 않고 재개한다. 사용자 발화는 이미 저장돼
        // 있으므로 다시 저장하면 같은 말이 대화 기록에 두 번 남는다.
        if (idempotencyKey != null) {
            Optional<MessageTurn> existing =
                    messageTurnRepository.findBySession_IdAndIdempotencyKey(sessionId, idempotencyKey);
            if (existing.isPresent()) {
                MessageTurn turn = existing.get();
                // 완료된 턴은 재생 대상이지 재개 대상이 아니다. 재생이 실패해 여기까지 흘러온
                // 경우라도 되돌리면 안 된다 — 저장된 응답이 고아가 되고 LLM 을 다시 호출한다.
                if (!turn.isResumable()) {
                    throw new BusinessException(ErrorCode.DUPLICATE_REQUEST);
                }
                log.info("Resuming turn: turnId={} previousStatus={} reason={}",
                        turn.getId(), turn.getStatus(), turn.getFinishedReason());
                turn.resume();
                return messageTurnRepository.save(turn);
            }
        }

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (!session.belongsTo(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (session.isEnded()) {
            throw new BusinessException(ErrorCode.SESSION_ALREADY_ENDED);
        }

        Message userMessage = saveMessage(session, user, MessageRole.USER, userContent,
                userSignal.emotionScore(), userSignal.biasType(), false);
        sessionRepository.incrementMessageCountAndSetLastMessageAt(
                session.getId(), 1, OffsetDateTime.now(ZoneOffset.UTC));

        return messageTurnRepository.save(
                MessageTurn.start(session, user, idempotencyKey, userMessage.getId()));
    }

    /**
     * 응답을 저장하고 턴을 완료로 확정한다.
     *
     * <p>{@code assistantContent} 가 null 이면 메시지를 남기지 않고 턴만 완료 처리한다 —
     * 위기 플로우·보안 거절은 고정 응답이라 대화 기록에 남기지 않는 경로가 있다.
     *
     * <p>{@code openTurn} 과 달리 세션 종료 여부를 확인하지 않는다. 턴 도중 세션이 종료돼도
     * (사용자 종료 또는 30분 타임아웃) 이미 사용자에게 내보낸 응답은 기록되어야 한다.
     * 이전에는 여기서 예외가 나면서 턴 전체가 버려졌다.
     */
    /**
     * @param leaseToken     {@code openTurn} 이 발급한 소유권 토큰. 다른 시도가 턴을 이어받았으면
     *                       아무것도 하지 않고 물러난다 — 응답 메시지도 저장하지 않는다.
     * @param crisisSeverity 위기 플로우로 끝난 턴이면 그 severity — 재생 시 핫라인 복원에 쓴다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeTurn(
            UUID turnId,
            UUID leaseToken,
            String assistantContent,
            boolean crisisFlowTriggered,
            String finishedReason,
            Integer crisisSeverity) {

        MessageTurn turn = messageTurnRepository.findById(turnId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        // 값싼 조기 확인. 최종 판정은 아래 조건부 UPDATE 가 한다.
        if (!turn.isHeldBy(leaseToken)) {
            log.warn("Turn lease lost, abandoning completion: turnId={} status={}", turnId, turn.getStatus());
            return;
        }

        UUID assistantMessageId = null;
        if (assistantContent != null && !assistantContent.isBlank()) {
            Message assistantMessage = saveMessage(turn.getSession(), turn.getUser(),
                    MessageRole.ASSISTANT, assistantContent, null, null, crisisFlowTriggered);
            assistantMessageId = assistantMessage.getId();
            sessionRepository.incrementMessageCountAndSetLastMessageAt(
                    turn.getSession().getId(), 1, OffsetDateTime.now(ZoneOffset.UTC));
        }

        // 소유권 확인과 상태 전이를 한 번의 UPDATE 로 한다. 위의 메모리 비교만으로는
        // compare-and-set 이 아니라, 그 사이에 재시도가 리스를 가져가도 덮어쓸 수 있다.
        int updated = messageTurnRepository.finishIfHeld(
                turnId, leaseToken, TurnStatus.COMPLETED, finishedReason,
                assistantMessageId, crisisSeverity, OffsetDateTime.now(ZoneOffset.UTC));

        if (updated == 0) {
            // 그 창에서 소유권을 잃었다. 이 트랜잭션을 통째로 롤백해 방금 저장한 응답 메시지도
            // 없앤다 — 어떤 턴도 참조하지 않는 고아 메시지를 남기지 않기 위해서다.
            log.warn("Turn lease lost during completion, rolling back: turnId={}", turnId);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    /**
     * 응답을 만들지 못하고 끝난 턴을 확정한다.
     *
     * <p><b>예외를 던지지 않는다.</b> 이미 실패 처리 중인 경로에서 호출되므로, 여기서 또 던지면
     * 원래 실패 원인을 덮어쓰고 턴이 {@code generating} 에 영원히 남는다.
     * ({@code SummaryStatusWriter.markFailed} 와 같은 이유)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failTurn(UUID turnId, UUID leaseToken, String finishedReason) {
        try {
            int updated = messageTurnRepository.finishIfHeld(
                    turnId, leaseToken, TurnStatus.FAILED, finishedReason,
                    null, null, OffsetDateTime.now(ZoneOffset.UTC));
            if (updated == 0) {
                log.warn("Turn lease lost or already terminal, skipping failure record: turnId={}", turnId);
            }
        } catch (Exception e) {
            log.error("Failed to mark turn as failed: turnId={} reason={}", turnId, finishedReason, e);
        }
    }

    /**
     * 진행 중임을 알린다 — {@code updated_at} 만 갱신한다.
     *
     * <p>이게 없으면 {@code updated_at} 이 턴을 연 시점에 고정되어, 파이프라인이 오래 걸리는
     * 턴이 "버려진 것"으로 오판된다. SSE 타임아웃은 클라이언트 연결만 닫고 백그라운드 처리는
     * 계속 돌기 때문이다.
     *
     * <p>실패는 삼킨다. 하트비트를 놓쳐도 리스 토큰이 최종 정합성을 지킨다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touchTurn(UUID turnId, UUID leaseToken) {
        try {
            int updated = messageTurnRepository.touchIfHeld(
                    turnId, leaseToken, OffsetDateTime.now(ZoneOffset.UTC));
            if (updated == 0) {
                log.debug("Turn heartbeat skipped — lease not held: turnId={}", turnId);
            }
        } catch (Exception e) {
            log.warn("Turn heartbeat failed: turnId={}", turnId, e);
        }
    }

    /**
     * 같은 세션에서 같은 Idempotency-Key 로 재시도한 턴을 찾는다.
     *
     * <p>세션 범위인 이유는 {@code MessageTurnRepository.findBySession_IdAndIdempotencyKey} 참조.
     */
    @Transactional(readOnly = true)
    public Optional<MessageTurn> findTurn(UUID sessionId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return messageTurnRepository.findBySession_IdAndIdempotencyKey(sessionId, idempotencyKey);
    }

    /** 완료된 턴의 응답 원문을 복호화해 돌려준다 — 재시도 재생용. */
    @Transactional(readOnly = true)
    public Optional<String> loadAssistantContent(UUID assistantMessageId) {
        if (assistantMessageId == null) {
            return Optional.empty();
        }
        return messageRepository.findById(assistantMessageId)
                .map(m -> new String(messageEncryptor.decrypt(m.getContentCiphertext()), StandardCharsets.UTF_8));
    }

    @Transactional(readOnly = true)
    public List<SafetyL1HistoryMessage> loadRecentUserSafetyHistory(UUID sessionId, int limit) {
        return messageRepository.findRecentBySessionAndRole(
                        sessionId,
                        MessageRole.USER,
                        PageRequest.of(0, limit)
                ).stream()
                .map(this::toSafetyHistoryMessage)
                .toList();
    }

    private SafetyL1HistoryMessage toSafetyHistoryMessage(Message message) {
        String normalizedContent = decryptAndNormalize(message);
        UserMessageSignal fallbackSignal = userMessageSignalAnalyzer.analyze(normalizedContent);
        Integer emotionScore = message.getEmotionScore() != null
                ? message.getEmotionScore()
                : fallbackSignal.emotionScore();
        String biasType = message.getBiasType() != null
                ? message.getBiasType()
                : fallbackSignal.biasType();
        return new SafetyL1HistoryMessage(normalizedContent, emotionScore, biasType);
    }

    private String decryptAndNormalize(Message message) {
        byte[] plaintext = messageEncryptor.decrypt(message.getContentCiphertext());
        return inputNormalizer.normalize(new String(plaintext, StandardCharsets.UTF_8));
    }

    private Message saveMessage(
            Session session,
            User user,
            MessageRole role,
            String content,
            Integer emotionScore,
            String biasType,
            boolean isCrisisFlagged) {

        byte[] ciphertext = messageEncryptor.encrypt(content.getBytes(StandardCharsets.UTF_8));
        Message message = Message.builder()
                .session(session)
                .user(user)
                .role(role)
                .contentCiphertext(ciphertext)
                .contentDekId(messageEncryptor.dekId())
                .emotionScore(emotionScore)
                .biasType(biasType)
                .isCrisisFlagged(isCrisisFlagged)
                .build();
        return messageRepository.save(message);
    }
}
