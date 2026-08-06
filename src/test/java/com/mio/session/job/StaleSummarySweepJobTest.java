package com.mio.session.job;

import com.mio.session.repository.SessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaleSummarySweepJobTest {

    @Test
    @DisplayName("컨솔리데이션이 유실된 pending 요약은 유예 시간이 지나면 실패로 정리한다")
    void run_marks_stale_pending_summaries_failed() {
        SessionRepository sessionRepository = mock(SessionRepository.class);
        when(sessionRepository.markStalePendingSummariesFailed(any())).thenReturn(2);
        StaleSummarySweepJob job = new StaleSummarySweepJob(sessionRepository);

        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        job.run();
        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);

        ArgumentCaptor<OffsetDateTime> cutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(sessionRepository).markStalePendingSummariesFailed(cutoffCaptor.capture());
        // 정상 컨솔리데이션(LLM 다단계 호출)이 끝나기 전에 가로채면 사용자가 받을 요약을 잃는다.
        // 유예를 줄이는 변경은 의도적이어야 하므로 30분을 실행 시간 오차만 허용해 고정한다.
        assertThat(cutoffCaptor.getValue())
                .isBetween(before.minusMinutes(30), after.minusMinutes(30));
    }

    @Test
    @DisplayName("정리 중 예외가 나도 스케줄러를 죽이지 않는다")
    void run_swallows_exception_to_keep_scheduler_alive() {
        SessionRepository sessionRepository = mock(SessionRepository.class);
        when(sessionRepository.markStalePendingSummariesFailed(any()))
                .thenThrow(new RuntimeException("db down"));
        StaleSummarySweepJob job = new StaleSummarySweepJob(sessionRepository);

        job.run();

        verify(sessionRepository).markStalePendingSummariesFailed(any());
    }
}
