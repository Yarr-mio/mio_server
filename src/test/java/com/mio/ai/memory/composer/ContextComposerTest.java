package com.mio.ai.memory.composer;

import com.mio.ai.memory.retrieval.RetrievalSource;
import com.mio.ai.memory.retrieval.RetrievedItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextComposerTest {

    @Test
    void includesActivatedBeliefNeighborsInBeliefContext() {
        ContextSanitizer sanitizer = mock(ContextSanitizer.class);
        InjectionScanner injectionScanner = mock(InjectionScanner.class);
        ContextComposer composer = new ContextComposer(sanitizer, injectionScanner);
        RetrievedItem belief = new RetrievedItem("belief-1", RetrievalSource.GRAPH_BELIEF_NEIGH,
                "core_self [negative] conf:0.70", "sensitive", 0.7, 1);
        when(sanitizer.sanitize(List.of(belief), "sensitive")).thenReturn(List.of(belief));
        when(injectionScanner.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        String context = composer.compose(List.of(belief), "sensitive", false);

        assertThat(context).contains("[Belief Context]");
        assertThat(context).contains("core_self [negative] conf:0.70");
    }
}
