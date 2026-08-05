package com.mio.ai.crisis;

import com.mio.session.domain.Session;
import com.mio.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 이슈 #256 — 실시간 하네스가 놓친 위기를 세션 종료 후 승격한다. */
class CrisisEpisodePromoterTest {

    private CrisisEventRepository crisisEventRepository;
    private CrisisEventRecorder crisisEventRecorder;
    private CrisisEpisodePromoter promoter;

    private User user;
    private Session session;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        crisisEventRepository = mock(CrisisEventRepository.class);
        crisisEventRecorder = mock(CrisisEventRecorder.class);
        promoter = new CrisisEpisodePromoter(crisisEventRepository, crisisEventRecorder);

        user = mock(User.class);
        session = mock(Session.class);
        sessionId = UUID.randomUUID();
        when(session.getId()).thenReturn(sessionId);
        when(crisisEventRecorder.record(any(), any(), anyInt(), anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("위기 세션인데 기록이 없으면 승격한다")
    void promotesCrisisEpisodeWithoutExistingEvent() {
        when(crisisEventRepository.existsBySession_Id(sessionId)).thenReturn(false);

        boolean promoted = promoter.promoteIfCrisis(user, session, "crisis");

        assertThat(promoted)
                .as("룰이 정상으로 본 발화는 어떤 LLM 도 보지 못한다 — 이 경로가 유일한 회수 지점이다")
                .isTrue();
        verify(crisisEventRecorder).record(eq(user), eq(session), eq(2), eq("pattern"));
    }

    @Test
    @DisplayName("이미 위기로 기록된 세션은 승격하지 않는다")
    void doesNotPromoteWhenSessionAlreadyHasCrisisEvent() {
        when(crisisEventRepository.existsBySession_Id(sessionId)).thenReturn(true);

        boolean promoted = promoter.promoteIfCrisis(user, session, "crisis");

        assertThat(promoted)
                .as("같은 위기를 두 번 세면 프로파일의 위기 빈도가 부풀고 검토 큐에도 중복이 쌓인다")
                .isFalse();
        verify(crisisEventRecorder, never()).record(any(), any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("위기가 아닌 세션 유형은 무시한다")
    void ignoresNonCrisisEpisodeTypes() {
        for (String episodeType : new String[]{"regular", "cbt_success", "cbt_partial", "support_only"}) {
            assertThat(promoter.promoteIfCrisis(user, session, episodeType)).isFalse();
        }

        verify(crisisEventRecorder, never()).record(any(), any(), anyInt(), anyString());
        verify(crisisEventRepository, never()).existsBySession_Id(any());
    }

    @Test
    @DisplayName("판정값이 없거나 엔티티가 없으면 아무 일도 하지 않는다")
    void ignoresMissingInputs() {
        assertThat(promoter.promoteIfCrisis(user, session, null)).isFalse();
        assertThat(promoter.promoteIfCrisis(null, session, "crisis")).isFalse();
        assertThat(promoter.promoteIfCrisis(user, null, "crisis")).isFalse();

        verify(crisisEventRecorder, never()).record(any(), any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("대소문자가 달라도 위기 판정으로 인식한다")
    void episodeTypeMatchingIsCaseInsensitive() {
        when(crisisEventRepository.existsBySession_Id(sessionId)).thenReturn(false);

        assertThat(promoter.promoteIfCrisis(user, session, "CRISIS")).isTrue();
    }

    @Test
    @DisplayName("기록에 실패하면 승격도 실패로 보고한다")
    void reportsFailureWhenRecordingFails() {
        when(crisisEventRepository.existsBySession_Id(sessionId)).thenReturn(false);
        when(crisisEventRecorder.record(any(), any(), anyInt(), anyString())).thenReturn(false);

        assertThat(promoter.promoteIfCrisis(user, session, "crisis"))
                .as("기록되지 않은 승격을 성공으로 세면 다음 세션의 보호 근거가 없는데 있다고 믿게 된다")
                .isFalse();
    }
}
