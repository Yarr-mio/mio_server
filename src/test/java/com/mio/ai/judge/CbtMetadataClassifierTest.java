package com.mio.ai.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.safety.UserMessageSignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CbtMetadataClassifierTest {

    @Test
    @DisplayName("LLM 응답이 markdown json fence로 감싸져도 파싱한다")
    void classify_stripsMarkdownJsonFence() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.completeJson(any(LlmRequest.class))).thenReturn("""
                ```json
                {
                  "cbt_intervention_state": "completed",
                  "completion_reason": "user_reframed_thought",
                  "requires_emotion_score": true,
                  "is_socratic": false,
                  "bias_type": "catastrophizing",
                  "reconstructed_thought": "최악은 아닐 수 있다"
                }
                ```
                """);
        CbtMetadataClassifier classifier = new CbtMetadataClassifier(llmClient, new ObjectMapper());

        CbtMetadataResult result = classifier.classify(
                "socratic_asked",
                List.of(),
                "다른 가능성도 있는 것 같아",
                "그 관점을 기억해볼까요?",
                new UserMessageSignal(45, "catastrophizing"),
                1,
                false
        );

        assertThat(result.state()).isEqualTo(CbtInterventionState.COMPLETED);
        assertThat(result.requiresEmotionScore()).isTrue();
        assertThat(result.biasType()).isEqualTo("catastrophizing");
    }

    @Test
    @DisplayName("이미 completed 상태인 후속 턴에서는 감정점수 target 생성을 다시 요구하지 않는다")
    void classify_completedPreviousState_suppressesDuplicateEmotionScoreTarget() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.completeJson(any(LlmRequest.class))).thenReturn("""
                {
                  "cbt_intervention_state": "completed",
                  "completion_reason": "user_reframed_thought",
                  "requires_emotion_score": true,
                  "is_socratic": false,
                  "bias_type": "catastrophizing",
                  "reconstructed_thought": "최악은 아닐 수 있다"
                }
                """);
        CbtMetadataClassifier classifier = new CbtMetadataClassifier(llmClient, new ObjectMapper());

        CbtMetadataResult result = classifier.classify(
                "completed",
                List.of(),
                "고마워. 이제 다른 얘기 해도 될까?",
                "물론이에요. 지금 떠오르는 이야기를 편하게 말해 주세요.",
                new UserMessageSignal(45, "catastrophizing"),
                1,
                false
        );

        assertThat(result.state()).isEqualTo(CbtInterventionState.COMPLETED);
        assertThat(result.requiresEmotionScore()).isFalse();
        assertThat(result.shouldCreateEmotionScoreTarget()).isFalse();
    }
}
