package com.mio.ai.memory.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class StructuredRetrieverTest {

    @Test
    void failsClosedWhenActivationSetContainsOnlyMalformedIds() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        StructuredRetriever retriever = new StructuredRetriever(jdbcTemplate);

        var result = retriever.retrieveBeliefNeighbors(UUID.randomUUID(), Set.of("not-a-uuid"));

        assertThat(result).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }
}
