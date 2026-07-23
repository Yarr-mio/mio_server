package com.mio.ai.memory.consolidation;

import com.mio.ai.memory.episodic.BeliefEvidence;
import com.mio.ai.memory.episodic.BeliefEvidenceRepository;
import com.mio.ai.memory.episodic.UserBelief;
import com.mio.ai.memory.episodic.UserBeliefRepository;
import com.mio.ai.memory.episodic.Thought;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 신념 증거 누적 및 Beta 분포 confidence 재계산.
 * confidence' = (1 + support_count) / (2 + support_count + contradict_count)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BeliefEvidenceAccumulator {

    private final UserBeliefRepository beliefRepository;
    private final BeliefEvidenceRepository evidenceRepository;

    @Transactional
    public void accumulate(UserBelief belief, BeliefEvidenceKind evidenceKind, UUID sessionId, Thought thought) {
        BeliefEvidence evidence = BeliefEvidence.builder()
                .belief(belief)
                .sessionId(sessionId)
                .messageId(thought == null ? null : thought.getMessageId())
                .thought(thought)
                .evidenceKind(evidenceKind.name().toLowerCase())
                .weight(1.0)
                .build();
        evidenceRepository.save(evidence);

        switch (evidenceKind) {
            case SUPPORT -> belief.addSupport(1.0);
            case CONTRADICT -> belief.addContradict(1.0);
            case REFRAME -> log.debug("BeliefEvidenceAccumulator: skipped confidence update for reframe");
        }
        beliefRepository.save(belief);

        log.debug("Belief confidence updated: beliefId={} kind={} confidence={}",
                belief.getId(), evidenceKind, belief.getConfidence());
    }
}
