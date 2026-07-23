package com.mio.ai.memory.ontology;

import com.mio.ai.memory.episodic.UserBelief;
import com.mio.ai.memory.episodic.UserBeliefRepository;
import com.mio.ai.memory.working.WorkingMemory;
import com.mio.session.domain.SessionStatus;
import com.mio.session.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReactiveOntologyActivatorTest {

    private final CbtDistortionDefRepository distortionRepository = mock(CbtDistortionDefRepository.class);
    private final UserBeliefRepository beliefRepository = mock(UserBeliefRepository.class);
    private final WorkingMemory workingMemory = mock(WorkingMemory.class);
    private final TurnOntologyExtractor turnOntologyExtractor = mock(TurnOntologyExtractor.class);
    private final OntologyValidator ontologyValidator = mock(OntologyValidator.class);
    private final SessionRepository sessionRepository = mock(SessionRepository.class);

    private ReactiveOntologyActivator activator;
    private UUID userId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        activator = new ReactiveOntologyActivator(
                distortionRepository, beliefRepository, workingMemory, turnOntologyExtractor, ontologyValidator,
                sessionRepository);
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        when(sessionRepository.existsByIdAndStatus(sessionId, SessionStatus.ACTIVE)).thenReturn(true);
    }

    @Test
    void activatesOnlySeedTriggerExplicitlyPresentInCurrentUtterance() {
        CbtDistortionDef distortion = mock(CbtDistortionDef.class);
        when(distortion.getTypicalTriggers()).thenReturn(List.of("업무 압박", "건강 걱정"));
        when(distortionRepository.findById("catastrophizing")).thenReturn(Optional.of(distortion));

        activator.activateVerifiedTriggers(sessionId, "업무 압박 때문에 최악일 것 같아", "catastrophizing");

        verify(workingMemory).addSessionTrigger(sessionId, "업무 압박");
        verify(workingMemory, never()).addSessionTrigger(sessionId, "건강 걱정");
    }

    @Test
    void activatesExistingBeliefAndSeedTriggerFromValidatedStructuredExtraction() {
        CbtDistortionDef distortion = mock(CbtDistortionDef.class);
        UserBelief matchingBelief = mock(UserBelief.class);
        UserBelief otherBelief = mock(UserBelief.class);
        UUID matchingBeliefId = UUID.randomUUID();

        when(turnOntologyExtractor.extract("발표에서 다들 나를 싫어하는 것 같아"))
                .thenReturn(new TurnOntologySignal("mind_reading", "core_other", "negative"));
        when(ontologyValidator.isValidDistortionCode("mind_reading")).thenReturn(true);
        when(distortion.getTypicalTriggers()).thenReturn(List.of("발표", "사회적 상황"));
        when(distortionRepository.findById("mind_reading")).thenReturn(Optional.of(distortion));
        when(beliefRepository.findByUser_IdAndStatus(userId, "active"))
                .thenReturn(List.of(matchingBelief, otherBelief));
        when(matchingBelief.getId()).thenReturn(matchingBeliefId);
        when(matchingBelief.getBeliefKind()).thenReturn("core_other");
        when(matchingBelief.getPolarity()).thenReturn("negative");
        when(otherBelief.getBeliefKind()).thenReturn("core_self");
        when(otherBelief.getPolarity()).thenReturn("negative");

        activator.activateBeliefs(userId, sessionId, "발표에서 다들 나를 싫어하는 것 같아");

        verify(workingMemory).addSessionTrigger(sessionId, "발표");
        verify(workingMemory).addActivatedBeliefId(sessionId, matchingBeliefId.toString());
    }

    @Test
    void ignoresUnknownStructuredDistortionWithoutWritingWorkingMemory() {
        when(turnOntologyExtractor.extract(anyString()))
                .thenReturn(new TurnOntologySignal("invented_code", "core_self", "negative"));
        when(ontologyValidator.isValidDistortionCode("invented_code")).thenReturn(false);

        activator.activateBeliefs(userId, sessionId, "무엇이든 안 될 것 같아");

        verify(workingMemory, never()).addSessionTrigger(eq(sessionId), anyString());
        verify(workingMemory, never()).addActivatedBeliefId(eq(sessionId), anyString());
        verify(beliefRepository, never()).findByUser_IdAndStatus(userId, "active");
    }

    @Test
    void doesNotActivateAmbiguousBeliefsThatShareKindAndPolarity() {
        UserBelief first = mock(UserBelief.class);
        UserBelief second = mock(UserBelief.class);
        when(turnOntologyExtractor.extract("나는 아무것도 못하는 사람인 것 같아"))
                .thenReturn(new TurnOntologySignal("overgeneralization", "core_self", "negative"));
        when(ontologyValidator.isValidDistortionCode("overgeneralization")).thenReturn(true);
        when(distortionRepository.findById("overgeneralization")).thenReturn(Optional.empty());
        when(beliefRepository.findByUser_IdAndStatus(userId, "active")).thenReturn(List.of(first, second));
        when(first.getBeliefKind()).thenReturn("core_self");
        when(first.getPolarity()).thenReturn("negative");
        when(second.getBeliefKind()).thenReturn("core_self");
        when(second.getPolarity()).thenReturn("negative");

        activator.activateBeliefs(userId, sessionId, "나는 아무것도 못하는 사람인 것 같아");

        verify(workingMemory, never()).addActivatedBeliefId(eq(sessionId), anyString());
    }

    @Test
    void skipsAsyncActivationWhenSessionHasAlreadyEnded() {
        when(sessionRepository.existsByIdAndStatus(sessionId, SessionStatus.ACTIVE)).thenReturn(false);

        activator.activateBeliefs(userId, sessionId, "발표에서 다들 나를 싫어하는 것 같아");

        verify(turnOntologyExtractor, never()).extract(anyString());
        verifyNoInteractions(beliefRepository, workingMemory);
    }
}
