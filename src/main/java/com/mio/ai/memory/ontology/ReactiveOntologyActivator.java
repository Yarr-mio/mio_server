package com.mio.ai.memory.ontology;

import com.mio.ai.memory.episodic.UserBeliefRepository;
import com.mio.ai.memory.working.WorkingMemory;
import com.mio.session.domain.SessionStatus;
import com.mio.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 현재 발화의 검증된 온톨로지 신호를 세션 WorkingMemory에만 반영한다.
 * 활성화는 다음 턴의 관계 검색에 사용되며 새로운 신념/증거를 영속화하지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReactiveOntologyActivator {

    private static final Set<String> VALID_BELIEF_KINDS = Set.of(
            "core_self", "core_other", "core_world", "intermediate_rule", "compensatory_strategy");
    private static final Set<String> VALID_POLARITIES = Set.of("positive", "negative", "neutral");

    private final CbtDistortionDefRepository distortionRepository;
    private final UserBeliefRepository beliefRepository;
    private final WorkingMemory workingMemory;
    private final TurnOntologyExtractor turnOntologyExtractor;
    private final OntologyValidator ontologyValidator;
    private final SessionRepository sessionRepository;

    /**
     * 규칙 기반으로 이미 감지된 왜곡에서만 같은 발화에 명시적으로 등장한 시드 트리거를 활성화한다.
     */
    public void activateVerifiedTriggers(UUID sessionId, String normalizedMessage, String distortionCode) {
        if (sessionId == null || normalizedMessage == null || normalizedMessage.isBlank()
                || distortionCode == null || distortionCode.isBlank()) {
            return;
        }
        try {
            distortionRepository.findById(distortionCode)
                    .map(CbtDistortionDef::getTypicalTriggers)
                    .orElse(List.of())
                    .stream()
                    .filter(Objects::nonNull)
                    .filter(trigger -> !trigger.isBlank())
                    .filter(trigger -> containsNormalized(normalizedMessage, trigger))
                    .forEach(trigger -> workingMemory.addSessionTrigger(sessionId, trigger));
        } catch (Exception e) {
            log.warn("ReactiveOntologyActivator: trigger activation skipped for sessionId={}", sessionId, e);
        }
    }

    /**
     * LLM 결과는 온톨로지와 DB 허용값으로 다시 검증한다. 이 호출은 응답 스트리밍을 지연시키지 않는다.
     */
    @Async("ontologyActivationExecutor")
    public void activateBeliefs(UUID userId, UUID sessionId, String normalizedMessage) {
        if (userId == null || sessionId == null || normalizedMessage == null || normalizedMessage.isBlank()) {
            return;
        }
        try {
            if (!isSessionActive(sessionId) || !workingMemory.tryAcquireOntologyActivation(sessionId)) {
                return;
            }
            TurnOntologySignal signal = turnOntologyExtractor.extract(normalizedMessage);
            if (!isSessionActive(sessionId) || !ontologyValidator.isValidDistortionCode(signal.distortionCode())) {
                return;
            }

            if (!VALID_BELIEF_KINDS.contains(signal.beliefKind()) || !VALID_POLARITIES.contains(signal.polarity())) {
                return;
            }

            List<String> activatedBeliefIds = beliefRepository.findByUser_IdAndStatus(userId, "active").stream()
                    .filter(belief -> signal.beliefKind().equals(belief.getBeliefKind()))
                    .filter(belief -> signal.polarity().equals(belief.getPolarity()))
                    .map(belief -> belief.getId())
                    .filter(Objects::nonNull)
                    .map(UUID::toString)
                    .toList();
            if (activatedBeliefIds.size() == 1 && isSessionActive(sessionId)) {
                workingMemory.addActivatedBeliefId(sessionId, activatedBeliefIds.getFirst());
            }
        } catch (Exception e) {
            log.warn("ReactiveOntologyActivator: belief activation skipped for sessionId={}", sessionId, e);
        }
    }

    private boolean containsNormalized(String message, String trigger) {
        return compact(message).contains(compact(trigger));
    }

    private String compact(String value) {
        return value.replaceAll("\\s+", "").trim();
    }

    private boolean isSessionActive(UUID sessionId) {
        return sessionRepository.existsByIdAndStatus(sessionId, SessionStatus.ACTIVE);
    }
}
