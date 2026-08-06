package com.mio.session.dto;

import com.mio.session.domain.Session;
import com.mio.session.domain.SessionSummary;
import com.mio.session.domain.SummaryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionSummaryResponseTest {

    private static final String INTERNAL = "사용자는 발표 불안을 이야기했다. 파국화 패턴이 감지되었다.";
    private static final String RENDERED = "오늘은 발표 걱정을 많이 이야기했어요.";

    @Test
    @DisplayName("렌더링된 사용자 요약이 있으면 그것을 노출한다")
    void prefersRenderedUserSummary() {
        var response = SessionSummaryResponse.from(session(), summary(RENDERED), List.of());

        assertThat(response.summary()).isEqualTo(RENDERED);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("렌더링된 요약이 없으면 내부 요약으로 폴백한다")
    void fallsBackToInternalSummary(String userSummaryText) {
        var response = SessionSummaryResponse.from(session(), summary(userSummaryText), List.of());

        assertThat(response.summary()).isEqualTo(INTERNAL);
    }

    // ── helpers ──────────────────────────────────────────────

    private Session session() {
        Session session = mock(Session.class);
        when(session.getSummaryStatus()).thenReturn(SummaryStatus.VIEWED);
        return session;
    }

    private SessionSummary summary(String userSummaryText) {
        SessionSummary summary = mock(SessionSummary.class);
        when(summary.getSummaryText()).thenReturn(INTERNAL);
        when(summary.getUserSummaryText()).thenReturn(userSummaryText);
        return summary;
    }
}
