package com.mio.ai.memory.consolidation;

import com.mio.session.domain.SummaryComponentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 핵심 요약과 독립적인 렌더링·Todo 작업의 terminal state를 기록한다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SummaryComponentStatusWriter {

    private static final String METRIC = "mio.summary.component";

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUserRenderDone(UUID sessionId) {
        update(Component.USER_RENDER, sessionId, SummaryComponentStatus.DONE, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUserRenderFailed(UUID sessionId, String errorCode) {
        update(Component.USER_RENDER, sessionId, SummaryComponentStatus.FAILED, errorCode);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markTodoDone(UUID sessionId) {
        update(Component.TODO, sessionId, SummaryComponentStatus.DONE, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markTodoSkipped(UUID sessionId) {
        update(Component.TODO, sessionId, SummaryComponentStatus.SKIPPED, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markTodoFailed(UUID sessionId, String errorCode) {
        update(Component.TODO, sessionId, SummaryComponentStatus.FAILED, errorCode);
    }

    /** 오래 pending인 파생 작업을 실패로 종결해 무한 로딩과 조용한 고착을 막는다. */
    @Transactional
    public SweepResult failStale(OffsetDateTime cutoff) {
        int renderFailed = failStale(Component.USER_RENDER, cutoff);
        int todoFailed = failStale(Component.TODO, cutoff);
        return new SweepResult(renderFailed, todoFailed);
    }

    private void update(Component component,
                        UUID sessionId,
                        SummaryComponentStatus status,
                        String errorCode) {
        try {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE session_summaries
                    SET %s = ?,
                        component_errors = CASE
                            WHEN CAST(? AS text) IS NULL THEN component_errors - '%s'
                            ELSE jsonb_set(component_errors, '{%s}',
                                    to_jsonb(CAST(? AS text)), true)
                        END,
                        updated_at = now()
                    WHERE session_id = ?
                    """.formatted(component.statusColumn, component.errorKey, component.errorKey),
                    status.value(), errorCode, errorCode, sessionId
            );
            String outcome = updated == 1 ? status.value() : "missing";
            meterRegistry.counter(METRIC, "component", component.metricTag, "outcome", outcome)
                    .increment();
            if (updated != 1) {
                log.warn("Summary component status row missing component={} sessionId={}",
                        component.metricTag, sessionId);
            }
        } catch (Exception e) {
            meterRegistry.counter(METRIC, "component", component.metricTag,
                    "outcome", "write_failed").increment();
            log.error("Failed to persist summary component status component={} sessionId={}",
                    component.metricTag, sessionId, e);
        }
    }

    private int failStale(Component component, OffsetDateTime cutoff) {
        int updated = jdbcTemplate.update(
                """
                UPDATE session_summaries
                SET %s = 'failed',
                    component_errors = jsonb_set(component_errors, '{%s}',
                            '"WORKER_STUCK"'::jsonb, true),
                    updated_at = now()
                WHERE %s = 'pending'
                  AND COALESCE(%s, created_at) <= ?
                """.formatted(component.statusColumn, component.errorKey,
                        component.statusColumn, component.pendingAtColumn),
                cutoff
        );
        if (updated > 0) {
            meterRegistry.counter(METRIC, "component", component.metricTag,
                    "outcome", "stuck_failed").increment(updated);
        }
        return updated;
    }

    private enum Component {
        USER_RENDER("user_render_status", "user_render_pending_at", "user_render", "user_render"),
        TODO("todo_status", "todo_pending_at", "todo", "todo");

        private final String statusColumn;
        private final String pendingAtColumn;
        private final String errorKey;
        private final String metricTag;

        Component(String statusColumn, String pendingAtColumn, String errorKey, String metricTag) {
            this.statusColumn = statusColumn;
            this.pendingAtColumn = pendingAtColumn;
            this.errorKey = errorKey;
            this.metricTag = metricTag;
        }
    }

    public record SweepResult(int userRenderFailed, int todoFailed) {
        public int total() {
            return userRenderFailed + todoFailed;
        }
    }
}
