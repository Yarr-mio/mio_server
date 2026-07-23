package com.mio.ai.memory.consolidation;

import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BeliefIdentityHasherTest {

    @Test
    void normalizesIdentityButScopesItsHmacToTheUser() {
        BeliefIdentityHasher hasher = new BeliefIdentityHasher("a-32-byte-or-longer-test-hmac-secret");
        UUID firstUser = UUID.randomUUID();
        UUID secondUser = UUID.randomUUID();

        byte[] normalized = hasher.hash(firstUser, "나는  충분하지  않아", BeliefIdentityHasher.CURRENT_VERSION);
        byte[] equivalent = hasher.hash(firstUser, " 나는 충분하지 않아 ", BeliefIdentityHasher.CURRENT_VERSION);
        byte[] otherUser = hasher.hash(secondUser, "나는 충분하지 않아", BeliefIdentityHasher.CURRENT_VERSION);

        assertThat(normalized).isEqualTo(equivalent);
        assertThat(normalized).isNotEqualTo(otherUser);
    }

    @Test
    void canonicalizesUnicodeAndCaseBeforeHashing() {
        BeliefIdentityHasher hasher = new BeliefIdentityHasher("a-32-byte-or-longer-test-hmac-secret");
        UUID userId = UUID.randomUUID();
        String decomposed = Normalizer.normalize("CAFÉ", Normalizer.Form.NFD);

        byte[] canonical = hasher.hash(userId, "café", BeliefIdentityHasher.CURRENT_VERSION);
        byte[] visuallyEquivalent = hasher.hash(userId, decomposed, BeliefIdentityHasher.CURRENT_VERSION);

        assertThat(visuallyEquivalent).isEqualTo(canonical);
    }
}
