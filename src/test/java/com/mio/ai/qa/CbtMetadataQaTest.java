package com.mio.ai.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.judge.CbtInterventionState;
import com.mio.ai.judge.CbtMetadataClassifier;
import com.mio.ai.judge.CbtMetadataResult;
import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.judge.RiskVerdict;
import com.mio.ai.judge.SecurityVerdict;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.memory.working.SessionDelta;
import com.mio.ai.memory.working.WorkingMessage;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.PolicyEngine;
import com.mio.ai.profile.SafetyProfile;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.safety.UserMessageSignal;
import com.mio.ai.security.SecurityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA 시나리오 SC-21 ~ SC-24
 * CBT 메타데이터 분류기가 위기/비위기/소크라테스 한도 상황에서 올바르게 동작하는지 검증한다.
 */
@DisplayName("[QA] CBT 메타데이터 분류기")
@ExtendWith(MockitoExtension.class)
class CbtMetadataQaTest {

    @Mock
    private LlmClient llmClient;

    private CbtMetadataClassifier classifier;
    private PolicyEngine policyEngine;
    private SafetyProfile defaultProfile;

    @BeforeEach
    void setUp() {
        classifier = new CbtMetadataClassifier(llmClient, new ObjectMapper());
        policyEngine = new PolicyEngine();
        defaultProfile = new SafetyProfile(
                "test_user", "default", Map.of(), List.of(), List.of(),
                List.of(), 0.0, 0, List.of()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SC-21: crisisFlowTriggered=true → LLM 호출 없이 none 반환
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SC-21: 위기 흐름 활성 시 LLM 호출 없이 NONE 상태를 반환한다")
    void sc21_crisisFlowTriggered_noneWithoutLlmCall() {
        CbtMetadataResult result = classifier.classify(
                "none",
                List.of(WorkingMessage.user("죽고싶다")),
                "죽고싶다",
                "위기 응답입니다.",
                null,
                0,
                true  // crisisFlowTriggered=true
        );

        assertThat(result.state()).isEqualTo(CbtInterventionState.NONE);
        assertThat(result.requiresEmotionScore()).isFalse();
        // LLM은 절대 호출되면 안 됨
        verify(llmClient, never()).completeJson(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SC-22: LLM이 socratic_asked 반환 → state=SOCRATIC_ASKED
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SC-22: LLM이 socratic_asked를 반환하면 state가 SOCRATIC_ASKED로 매핑된다")
    void sc22_llmReturnsSocraticAsked_stateCorrectlyMapped() throws Exception {
        String llmJson = """
                {
                  "cbt_intervention_state": "socratic_asked",
                  "completion_reason": null,
                  "requires_emotion_score": false,
                  "is_socratic": true,
                  "bias_type": "catastrophizing",
                  "reconstructed_thought": null
                }
                """;
        when(llmClient.completeJson(any())).thenReturn(llmJson);

        CbtMetadataResult result = classifier.classify(
                "none",
                List.of(
                        WorkingMessage.user("이번에도 망할 것 같아"),
                        WorkingMessage.assistant("그 생각이 얼마나 사실에 가깝게 느껴지시나요?")
                ),
                "그럴 것 같아요",
                "어떤 근거로 그렇게 생각하시나요?",
                new UserMessageSignal(40, "catastrophizing"),
                1,
                false
        );

        assertThat(result.state()).isEqualTo(CbtInterventionState.SOCRATIC_ASKED);
        assertThat(result.socratic()).isTrue();
        assertThat(result.requiresEmotionScore()).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SC-23: PolicyEngine - 소크라테스 2회 도달 → InterventionHints 비어있음 (MIO-CBT-011)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SC-23: 소크라테스 질문 2회 도달 시 PolicyEngine이 빈 InterventionHints를 반환한다 (MIO-CBT-011)")
    void sc23_socraticLimitReached_emptyInterventionHints() {
        var combined = new CombinedSignal(
                SecurityLevel.CLEAN, false, true, false, false, false,
                false, true,
                SafetyL1Result.clear(), 0.6
        );
        var judgeResult = new InputJudgeResult(
                SecurityVerdict.clean(),
                new RiskVerdict(RiskLevel.MEDIUM, List.of(), GenerationMode.SUPPORTIVE, DeliveryMode.CAUTIOUS_SPECULATIVE, false),
                0.65
        );
        // 소크라테스 질문 2회 → socraticLimitReached()=true
        var sessionDelta = new SessionDelta(2, "socratic_asked", new HashMap<>(), 0, new HashSet<>(), new HashSet<>());

        var decision = policyEngine.decide(combined, judgeResult, defaultProfile, sessionDelta);

        assertThat(decision.generationMode()).isEqualTo(GenerationMode.SUPPORTIVE);
        assertThat(decision.interventionHints()).isNotNull();
        assertThat(decision.interventionHints().suggestedCodes()).isEmpty();
        // LLM은 이 테스트에서 전혀 관여 없음
        verify(llmClient, never()).completeJson(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SC-24: LLM이 completed + requires_emotion_score=true 반환 → requiresEmotionScore=true
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SC-24: LLM이 completed+requiresEmotionScore를 반환하면 감정점수 타겟 생성이 요청된다")
    void sc24_llmReturnsCompleted_requiresEmotionScore() throws Exception {
        String llmJson = """
                {
                  "cbt_intervention_state": "completed",
                  "completion_reason": "user_reframed_thought",
                  "requires_emotion_score": true,
                  "is_socratic": false,
                  "bias_type": "overgeneralization",
                  "reconstructed_thought": "이번 한 번의 실패가 항상 실패한다는 뜻은 아니야"
                }
                """;
        when(llmClient.completeJson(any())).thenReturn(llmJson);

        CbtMetadataResult result = classifier.classify(
                "socratic_asked",
                List.of(
                        WorkingMessage.user("항상 이런 식인 것 같아"),
                        WorkingMessage.assistant("'항상'이라는 생각의 근거는 무엇인가요?"),
                        WorkingMessage.user("생각해보니 항상은 아닌 것 같아요")
                ),
                "이번 한 번이었던 것 같아요",
                "그렇군요! 이번에 새로운 시각을 갖게 되셨네요.",
                new UserMessageSignal(60, "overgeneralization"),
                1,
                false
        );

        assertThat(result.state()).isEqualTo(CbtInterventionState.COMPLETED);
        assertThat(result.requiresEmotionScore()).isTrue();
        assertThat(result.biasType()).isEqualTo("overgeneralization");
        assertThat(result.reconstructedThought()).isNotBlank();
        assertThat(result.shouldCreateEmotionScoreTarget()).isTrue();
    }
}
