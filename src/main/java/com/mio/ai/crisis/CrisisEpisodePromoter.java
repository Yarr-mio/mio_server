package com.mio.ai.crisis;

import com.mio.session.domain.Session;
import com.mio.session.repository.SessionRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 세션 종료 후 ExtractorLLM 이 위기로 판정한 세션을 위기 이벤트로 승격한다 (이슈 #256).
 *
 * <p>이 경로가 필요한 이유는 구조적이다. {@code InputJudge} 는 {@code SafetySignalCombiner} 가
 * 검증을 요구할 때만 호출되고, 그 조건은 전적으로 룰 레이어 결과로 정해진다. 즉 <b>Judge 의
 * 회수율 상한은 룰의 회수율과 같고</b>, 룰이 정상으로 본 발화는 어떤 LLM 도 보지 못한다.
 * ExtractorLLM 은 세션 전체를 읽고 독립적으로 판정하므로 그 한계를 넘는 유일한 기존 경로다.
 *
 * <p>승격의 효과는 학습 루프 복구다. {@code SafetyProfileBuilder} 는 {@code crisis_events} 를
 * 읽어 다음 세션의 임계값과 {@code force_judge} 를 정한다. 미탐이면 행이 생기지 않아 같은
 * 사용자가 같은 이유로 계속 미탐되는데, 이 승격이 그 고리를 끊는다.
 *
 * <p><b>이 클래스는 예외를 밖으로 내보내지 않는다.</b> 호출부는 이미 커밋된 세션 요약 뒤에서
 * 돌기 때문이다. 승격 경로의 DB 실패가 위로 전파되면 사용자가 LLM 비용을 치르고 받은 요약이
 * 함께 사라진다 — 이슈 #219 에서 한 번 겪은 사고다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CrisisEpisodePromoter {

    private static final String CRISIS_EPISODE = "crisis";

    /** 사후 판정이므로 {@code pattern} 이다 — 키워드·moderation 근거로 잡힌 것이 아니다. */
    private static final String TRIGGER_TYPE = "pattern";

    /**
     * 승격 이벤트의 심각도.
     *
     * <p>실시간 위기 플로우는 발화 근거로 1~3을 가른다. 사후 승격은 그 근거가 없다 — 세션
     * 전체를 요약한 판정이라 즉시성과 계획 여부를 확인할 수 없다. 그래서 최고 등급은 쓰지
     * 않는다. 반대로 1은 {@code SafetyProfileBuilder} 의 {@code crisisMax >= 2} 조건을
     * 넘지 못해 {@code force_judge} 도 민감 임계값도 켜지지 않는다 — 승격의 목적이 사라진다.
     */
    private static final int PROMOTED_SEVERITY = 2;

    private final CrisisEventRepository crisisEventRepository;
    private final CrisisEventRecorder crisisEventRecorder;
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;

    /**
     * @param episodeType ExtractorLLM 이 판정한 세션 유형
     * @return 이번 호출이 승격을 기록했는지. 이미 위기로 기록된 세션이거나 실패면 {@code false}
     */
    public boolean promoteIfCrisis(UUID userId, UUID sessionId, String episodeType) {
        if (userId == null || sessionId == null || !CRISIS_EPISODE.equalsIgnoreCase(episodeType)) {
            return false;
        }
        try {
            return promote(userId, sessionId);
        } catch (DataIntegrityViolationException e) {
            // 같은 세션에 대한 승격이 동시에 두 번 들어온 경우다. DB 의 부분 유니크 인덱스가
            // 두 번째를 막았으므로 이미 목적은 달성됐다.
            log.debug("CrisisEpisodePromoter: sessionId={} already promoted concurrently", sessionId);
            return false;
        } catch (Exception e) {
            // 여기서 던지면 이미 커밋된 요약 뒤의 후처리가 실패로 기록된다. 승격은 사후
            // 안전망이므로 실패해도 요약과 나머지 후처리를 막지 않는다.
            log.error("CrisisEpisodePromoter: promotion failed sessionId={}", sessionId, e);
            return false;
        }
    }

    private boolean promote(UUID userId, UUID sessionId) {
        // 실시간 플로우가 이미 기록한 세션은 건너뛴다. 같은 위기를 두 번 기록하면 운영자
        // 검토 큐에 중복이 쌓이고, 사후 승격인지 실시간 감지인지 구분이 흐려진다.
        if (crisisEventRepository.existsBySession_Id(sessionId)) {
            log.debug("CrisisEpisodePromoter: sessionId={} already has a crisis event, skipping", sessionId);
            return false;
        }

        User user = userRepository.findById(userId).orElse(null);
        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (user == null || session == null) {
            log.warn("CrisisEpisodePromoter: user or session not found userId={} sessionId={}", userId, sessionId);
            return false;
        }

        boolean recorded = crisisEventRecorder.record(user, session, PROMOTED_SEVERITY, TRIGGER_TYPE);
        if (recorded) {
            // 실시간 하네스가 놓친 위기다. 사람이 볼 수 있게 경고로 남긴다.
            log.warn("CrisisEpisodePromoter: promoted missed crisis sessionId={} severity={}",
                    sessionId, PROMOTED_SEVERITY);
        } else {
            log.error("CrisisEpisodePromoter: failed to record promoted crisis sessionId={}", sessionId);
        }
        return recorded;
    }
}
