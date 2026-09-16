package com.mio.ai.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.ModelCatalog;
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
        CbtMetadataClassifier classifier = new CbtMetadataClassifier(llmClient, new ObjectMapper(),
                ModelCatalog.defaults());

        CbtMetadataResult result = classifier.classify(
                "socratic_asked",
                List.of(),
                "다른 가능성도 있는 것 같아",
                "그 관점을 기억해볼까요?",
                new UserMessageSignal(45, "catastrophizing"),
                1,
                false,
                null,
                null
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
        CbtMetadataClassifier classifier = new CbtMetadataClassifier(llmClient, new ObjectMapper(),
                ModelCatalog.defaults());

        CbtMetadataResult result = classifier.classify(
                "completed",
                List.of(),
                "고마워. 이제 다른 얘기 해도 될까?",
                "물론이에요. 지금 떠오르는 이야기를 편하게 말해 주세요.",
                new UserMessageSignal(45, "catastrophizing"),
                1,
                false,
                null,
                null
        );

        assertThat(result.state()).isEqualTo(CbtInterventionState.COMPLETED);
        assertThat(result.requiresEmotionScore()).isFalse();
        assertThat(result.shouldCreateEmotionScoreTarget()).isFalse();
    }

    @Test
    @DisplayName("세그먼트 content가 실제 응답의 정확한 부분 문자열이면 순서대로 그대로 채택한다 (이슈 #549)")
    void classify_validSegments_areAdoptedInOrder() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.completeJson(any(LlmRequest.class))).thenReturn("""
                {
                  "cbt_intervention_state": "socratic_asked",
                  "completion_reason": null,
                  "requires_emotion_score": false,
                  "is_socratic": true,
                  "bias_type": "catastrophizing",
                  "reconstructed_thought": null,
                  "segments": [
                    {"type": "question", "content": "그 생각의 근거는 뭐라고 생각하세요?"},
                    {"type": "reflection", "content": "그런 마음이 드시는군요."}
                  ]
                }
                """);
        CbtMetadataClassifier classifier = new CbtMetadataClassifier(llmClient, new ObjectMapper(),
                ModelCatalog.defaults());

        CbtMetadataResult result = classifier.classify(
                "none", List.of(), "시험 망하면 끝이야",
                "그런 마음이 드시는군요. 그 생각의 근거는 뭐라고 생각하세요?",
                new UserMessageSignal(60, "catastrophizing"), 0, false, null, null);

        assertThat(result.segments()).extracting("type", "content").containsExactly(
                org.assertj.core.groups.Tuple.tuple("reflection", "그런 마음이 드시는군요."),
                org.assertj.core.groups.Tuple.tuple("question", "그 생각의 근거는 뭐라고 생각하세요?"));
    }

    @Test
    @DisplayName("세그먼트 content가 실제 응답에 없는 문자열(paraphrase)이면 전체를 reflection 하나로 폴백한다 (이슈 #549)")
    void classify_segmentContentNotVerbatim_fallsBackToSingleSegment() {
        LlmClient llmClient = mock(LlmClient.class);
        String actualResponse = "그런 마음이 드시는군요. 그 생각의 근거는 뭐라고 생각하세요?";
        when(llmClient.completeJson(any(LlmRequest.class))).thenReturn("""
                {
                  "cbt_intervention_state": "socratic_asked",
                  "completion_reason": null,
                  "requires_emotion_score": false,
                  "is_socratic": true,
                  "bias_type": "catastrophizing",
                  "reconstructed_thought": null,
                  "segments": [
                    {"type": "reflection", "content": "많이 힘드시겠어요."},
                    {"type": "question", "content": "그 생각의 근거는 뭐라고 생각하세요?"}
                  ]
                }
                """);
        CbtMetadataClassifier classifier = new CbtMetadataClassifier(llmClient, new ObjectMapper(),
                ModelCatalog.defaults());

        CbtMetadataResult result = classifier.classify(
                "none", List.of(), "시험 망하면 끝이야", actualResponse,
                new UserMessageSignal(60, "catastrophizing"), 0, false, null, null);

        assertThat(result.segments()).containsExactly(
                new CbtMetadataResult.CbtSegment("reflection", actualResponse));
    }

    @Test
    @DisplayName("segments 필드가 없으면 응답 전체를 reflection 하나로 담는다 (이슈 #549)")
    void classify_missingSegmentsField_fallsBackToSingleSegment() {
        LlmClient llmClient = mock(LlmClient.class);
        String actualResponse = "그런 마음이 드시는군요.";
        when(llmClient.completeJson(any(LlmRequest.class))).thenReturn("""
                {
                  "cbt_intervention_state": "none",
                  "completion_reason": null,
                  "requires_emotion_score": false,
                  "is_socratic": false,
                  "bias_type": null,
                  "reconstructed_thought": null
                }
                """);
        CbtMetadataClassifier classifier = new CbtMetadataClassifier(llmClient, new ObjectMapper(),
                ModelCatalog.defaults());

        CbtMetadataResult result = classifier.classify(
                "none", List.of(), "오늘 힘들었어", actualResponse,
                new UserMessageSignal(50, null), 0, false, null, null);

        assertThat(result.segments()).containsExactly(
                new CbtMetadataResult.CbtSegment("reflection", actualResponse));
    }
}
