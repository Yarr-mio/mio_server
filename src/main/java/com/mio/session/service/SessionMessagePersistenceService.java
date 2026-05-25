package com.mio.session.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionMessagePersistenceService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final MessageEncryptor messageEncryptor;

    @Transactional
    public void saveConversation(UUID sessionId, UUID userId, String userContent, String assistantContent) {
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

        saveMessage(session, user, MessageRole.USER, userContent);
        saveMessage(session, user, MessageRole.ASSISTANT, assistantContent);
        sessionRepository.incrementMessageCountAndSetLastMessageAt(session.getId(), 2, OffsetDateTime.now());
    }

    private void saveMessage(Session session, User user, MessageRole role, String content) {
        byte[] ciphertext = messageEncryptor.encrypt(content.getBytes(StandardCharsets.UTF_8));
        Message message = Message.builder()
                .session(session)
                .user(user)
                .role(role)
                .contentCiphertext(ciphertext)
                .contentDekId(messageEncryptor.dekId())
                .isCrisisFlagged(false)
                .build();
        messageRepository.save(message);
    }
}
