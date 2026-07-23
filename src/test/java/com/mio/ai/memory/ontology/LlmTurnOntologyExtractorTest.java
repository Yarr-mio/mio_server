package com.mio.ai.memory.ontology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmTurnOntologyExtractorTest {

    @Test
    void parsesCodeFenceAndLiteralNullAsAbsentValues() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.completeJson(any())).thenReturn("""
                ```json
                {"distortionCode":"mind_reading","beliefKind":"null","polarity":null}
                ```
                """);
        LlmTurnOntologyExtractor extractor = new LlmTurnOntologyExtractor(llmClient, new ObjectMapper());

        TurnOntologySignal signal = extractor.extract("다들 나를 싫어하는 것 같아");

        assertThat(signal.distortionCode()).isEqualTo("mind_reading");
        assertThat(signal.beliefKind()).isNull();
        assertThat(signal.polarity()).isNull();
    }
}
