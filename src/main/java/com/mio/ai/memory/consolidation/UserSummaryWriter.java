package com.mio.ai.memory.consolidation;

import com.mio.session.repository.SessionSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 사용자 노출용 요약을 독립 트랜잭션으로 기록한다 (이슈 #339).
 *
 * <p>렌더링은 블로킹 LLM 호출이라 요약 트랜잭션 밖에서 수행한다(이슈 #228 에서 Todo 개인화를
 * 트랜잭션 밖으로 뺀 것과 같은 이유). 그래서 쓰기만 짧은 REQUIRES_NEW 로 분리한다.
 *
 * <p>실패해도 예외를 밖으로 던지지 않는다 — 이 컬럼이 비면 조회 시 기존 요약으로 폴백되므로
 * 사용자에게 보이는 산출물은 유지된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSummaryWriter {

    private final SessionSummaryRepository sessionSummaryRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(UUID sessionId, String userSummaryText) {
        try {
            int updated = sessionSummaryRepository.updateUserSummaryText(sessionId, userSummaryText);
            if (updated == 0) {
                log.warn("[SummaryRenderer] no summary row to update sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.error("[SummaryRenderer] failed to persist user summary sessionId={}", sessionId, e);
        }
    }
}
