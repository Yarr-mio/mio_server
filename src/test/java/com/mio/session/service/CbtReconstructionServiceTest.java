package com.mio.session.service;

import com.mio.ai.judge.CbtInterventionState;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CbtReconstructionServiceTest {

    @Mock private CbtReconstructionRepository cbtReconstructionRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private MessageEncryptor messageEncryptor;

    private CbtReconstructionService service;
    private UUID userId;
    private User user;
    private Session session;

    @BeforeEach
    void setUp() {
        service = new CbtReconstructionService(
                cbtReconstructionRepository,
                sessionRepository,
                userRepository,
                messageEncryptor
        );
        userId = UUID.randomUUID();
        user = User.builder()
                .socialProvider("kakao")
                .socialId("social-id")
                .privacyConsent(true)
                .build();
        ReflectionTestUtils.setField(user, "id", userId);
        session = Session.builder()
                .user(user)
                .characterId("mio")
                .build();
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
    }

    @Test
    @DisplayName("completed 메타데이터로 CBT 감정 점수 target을 생성한다")
    void createEmotionScoreTarget_savesReconstruction() {
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(messageEncryptor.encrypt(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageEncryptor.dekId()).thenReturn("dek-test");
        when(cbtReconstructionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CbtMetadataResult metadata = new CbtMetadataResult(
                CbtInterventionState.COMPLETED,
                "user_reframed_thought",
                true,
                false,
                "catastrophizing",
                "최악은 아닐 수 있다"
        );

        service.createEmotionScoreTarget(
                userId,
                session.getId(),
                "다 망한 것 같아",
                "다른 가능성도 같이 볼 수 있어요.",
                metadata,
                45
        );

        ArgumentCaptor<CbtReconstruction> captor = ArgumentCaptor.forClass(CbtReconstruction.class);
        verify(cbtReconstructionRepository).save(captor.capture());
        CbtReconstruction saved = captor.getValue();
        assertThat(saved.getBiasType()).isEqualTo("catastrophizing");
        assertThat(saved.getEmotionScoreBefore()).isEqualTo(45);
        assertThat(new String(saved.getDistortedThoughtCiphertext(), StandardCharsets.UTF_8))
                .isEqualTo("다 망한 것 같아");
    }

    @Test
    @DisplayName("CBT 개입 후 감정 점수를 1회 제출한다")
    void submitEmotionScore_updatesAfterScore() {
        UUID reconstructionId = UUID.randomUUID();
        CbtReconstruction reconstruction = reconstruction(reconstructionId, 62);
        when(cbtReconstructionRepository.submitEmotionScoreAfterIfPending(
                org.mockito.ArgumentMatchers.eq(reconstructionId),
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(62),
                any()
        )).thenReturn(1);
        when(cbtReconstructionRepository.findById(reconstructionId)).thenReturn(Optional.of(reconstruction));

        CbtEmotionScoreResponse response =
                service.submitEmotionScore(userId, reconstructionId, new EmotionScoreRequest(62));

        assertThat(response.reconstructionId()).isEqualTo(reconstructionId);
        assertThat(response.emotionScoreAfter()).isEqualTo(62);
        assertThat(response.updatedAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 제출된 CBT 감정 점수는 다시 제출할 수 없다")
    void submitEmotionScore_alreadySubmitted_throws() {
        UUID reconstructionId = UUID.randomUUID();
        CbtReconstruction reconstruction = reconstruction(reconstructionId, 62);
        when(cbtReconstructionRepository.submitEmotionScoreAfterIfPending(
                org.mockito.ArgumentMatchers.eq(reconstructionId),
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(70),
                any()
        )).thenReturn(0);
        when(cbtReconstructionRepository.findById(reconstructionId)).thenReturn(Optional.of(reconstruction));

        assertThatThrownBy(() -> service.submitEmotionScore(userId, reconstructionId, new EmotionScoreRequest(70)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.CBT_SCORE_NOT_REQUIRED));
    }

    private CbtReconstruction reconstruction(UUID id, Integer afterScore) {
        CbtReconstruction reconstruction = CbtReconstruction.builder()
                .user(user)
                .session(session)
                .biasType("catastrophizing")
                .distortedThoughtCiphertext("distorted".getBytes(StandardCharsets.UTF_8))
                .distortedThoughtDekId("dek-test")
                .reconstructedThoughtCiphertext("reconstructed".getBytes(StandardCharsets.UTF_8))
                .reconstructedThoughtDekId("dek-test")
                .emotionScoreBefore(45)
                .emotionScoreAfter(afterScore)
                .build();
        ReflectionTestUtils.setField(reconstruction, "id", id);
        ReflectionTestUtils.setField(reconstruction, "updatedAt", OffsetDateTime.now(ZoneOffset.UTC));
        return reconstruction;
    }
}
