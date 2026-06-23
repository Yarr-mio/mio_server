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
import com.mio.session.domain.Session;
import com.mio.session.repository.MessageRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionMessagePersistenceService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final MessageEncryptor messageEncryptor;
    private final InputNormalizer inputNormalizer;
    private final UserMessageSignalAnalyzer userMessageSignalAnalyzer;

    @Transactional
    public void saveConversation(
            UUID sessionId,
            UUID userId,
            String userContent,
            String assistantContent,
            UserMessageSignal userSignal,
            boolean crisisFlowTriggered) {

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

        saveMessage(session, user, MessageRole.USER, userContent, userSignal.emotionScore(), userSignal.biasType(), false);
        saveMessage(session, user, MessageRole.ASSISTANT, assistantContent, null, null, crisisFlowTriggered);
        sessionRepository.incrementMessageCountAndSetLastMessageAt(session.getId(), 2, OffsetDateTime.now(ZoneOffset.UTC));
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

    private void saveMessage(
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
        messageRepository.save(message);
    }
}
