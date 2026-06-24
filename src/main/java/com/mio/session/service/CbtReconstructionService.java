package com.mio.session.service;

import com.mio.ai.judge.CbtMetadataResult;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.CbtReconstruction;
import com.mio.session.domain.Session;
import com.mio.session.dto.CbtEmotionScoreResponse;
import com.mio.session.dto.EmotionScoreRequest;
import com.mio.session.repository.CbtReconstructionRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CbtReconstructionService {

    private final CbtReconstructionRepository cbtReconstructionRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final MessageEncryptor messageEncryptor;

    @Transactional
    public CbtReconstruction createEmotionScoreTarget(
            UUID userId,
            UUID sessionId,
            String distortedThought,
            String assistantResponse,
            CbtMetadataResult metadata,
            Integer emotionScoreBefore) {

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        if (!session.belongsTo(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        User user = userRepository.getReferenceById(userId);

        String reconstructedThought = metadata.reconstructedThought() != null
                ? metadata.reconstructedThought()
                : assistantResponse;

        CbtReconstruction reconstruction = CbtReconstruction.builder()
                .user(user)
                .session(session)
                .message(null)
                .biasType(metadata.biasType())
                .distortedThoughtCiphertext(encrypt(distortedThought))
                .distortedThoughtDekId(messageEncryptor.dekId())
                .reconstructedThoughtCiphertext(encrypt(reconstructedThought))
                .reconstructedThoughtDekId(messageEncryptor.dekId())
                .emotionScoreBefore(emotionScoreBefore)
                .emotionScoreAfter(null)
                .build();

        return cbtReconstructionRepository.save(reconstruction);
    }

    @Transactional
    public CbtEmotionScoreResponse submitEmotionScore(
            UUID userId,
            UUID reconstructionId,
            EmotionScoreRequest request) {

        int updated = cbtReconstructionRepository.submitEmotionScoreAfterIfPending(
                reconstructionId,
                userId,
                request.score(),
                OffsetDateTime.now(ZoneOffset.UTC));
        if (updated == 1) {
            CbtReconstruction updatedReconstruction = cbtReconstructionRepository.findById(reconstructionId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CBT_RECONSTRUCTION_NOT_FOUND));
            return CbtEmotionScoreResponse.from(updatedReconstruction);
        }

        CbtReconstruction reconstruction = cbtReconstructionRepository.findById(reconstructionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CBT_RECONSTRUCTION_NOT_FOUND));
        if (!reconstruction.getSession().belongsTo(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        throw new BusinessException(ErrorCode.CBT_SCORE_NOT_REQUIRED);
    }

    private byte[] encrypt(String content) {
        String safeContent = content == null ? "" : content;
        return messageEncryptor.encrypt(safeContent.getBytes(StandardCharsets.UTF_8));
    }
}
