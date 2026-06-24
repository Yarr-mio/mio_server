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
        when(llmClient.complete(any(LlmRequest.class))).thenReturn("""
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
}
