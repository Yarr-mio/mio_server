package com.mio.ai.crisis;

import com.mio.session.domain.Session;
import com.mio.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
 * <p>추가 비용은 없다. ExtractorLLM 호출은 이미 세션마다 발생하고 그 결과를 읽기만 한다.
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
     * 않고, 동시에 프로파일이 무시하지 않도록 최저 등급보다는 높게 둔다.
     */
    private static final int PROMOTED_SEVERITY = 2;

    private final CrisisEventRepository crisisEventRepository;
    private final CrisisEventRecorder crisisEventRecorder;

    /**
     * @param episodeType ExtractorLLM 이 판정한 세션 유형
     * @return 이번 호출이 승격을 기록했는지. 이미 위기로 기록된 세션이면 {@code false}
     */
    public boolean promoteIfCrisis(User user, Session session, String episodeType) {
        if (user == null || session == null || !CRISIS_EPISODE.equalsIgnoreCase(episodeType)) {
            return false;
        }
        // 실시간 플로우가 이미 기록한 세션은 건너뛴다. 같은 위기를 두 번 세면 프로파일의
        // 위기 빈도가 부풀고, 운영자 검토 큐에도 중복이 쌓인다.
        if (crisisEventRepository.existsBySession_Id(session.getId())) {
            log.debug("CrisisEpisodePromoter: sessionId={} already has a crisis event, skipping",
                    session.getId());
            return false;
        }

        boolean recorded = crisisEventRecorder.record(user, session, PROMOTED_SEVERITY, TRIGGER_TYPE);
        if (recorded) {
            // 실시간 하네스가 놓친 위기다. 사람이 볼 수 있게 경고로 남긴다.
            log.warn("CrisisEpisodePromoter: promoted missed crisis sessionId={} severity={}",
                    session.getId(), PROMOTED_SEVERITY);
        } else {
            log.error("CrisisEpisodePromoter: failed to record promoted crisis sessionId={}",
                    session.getId());
        }
        return recorded;
    }
}
