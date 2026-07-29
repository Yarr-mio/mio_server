package com.mio.ai.crisis;

import com.mio.crisis.domain.CrisisEvent;
import com.mio.session.domain.Session;
import com.mio.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 위기 이벤트를 한 번 저장한다 (이슈 P0-B).
 *
 * <p>재시도 루프와 분리된 별도 빈인 이유는 두 가지다.
 * <ul>
 *   <li>{@code REQUIRES_NEW} 가 프록시를 거쳐야 실제로 새 트랜잭션이 열린다. 같은 클래스 안에서
 *       호출하면 self-invocation 이라 무시된다 ({@code SummaryStatusWriter} 와 같은 이유)</li>
 *   <li>시도마다 트랜잭션이 새로 열려야 한다. 한 트랜잭션 안에서 재시도하면 첫 실패로 이미
 *       rollback-only 가 찍혀 이후 시도가 전부 실패한다</li>
 * </ul>
 *
 * <p><b>예외를 삼키지 않는다.</b> 저장 실패를 알아야 재시도·안전 래치로 이어갈 수 있다.
 */
@Component
@RequiredArgsConstructor
class CrisisEventWriter {

    private final CrisisEventRepository crisisEventRepository;

    /**
     * @param dedupKey 논리 이벤트의 안정 키. 재시도가 같은 키로 들어오면 새로 만들지 않고
     *                 기존 행을 돌려준다 — 커밋은 성공했지만 응답이 유실된 경우를 위해서다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID write(User user, Session session, int severity, String triggerType, UUID dedupKey) {
        Optional<CrisisEvent> existing = crisisEventRepository.findByDedupKey(dedupKey);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        CrisisEvent event = CrisisEvent.builder()
                .user(user)
                .session(session)
                .dedupKey(dedupKey)
                .triggerType(triggerType)
                .severity(severity)
                .operatorReviewed(false)
                .build();
        return crisisEventRepository.save(event).getId();
    }
}
