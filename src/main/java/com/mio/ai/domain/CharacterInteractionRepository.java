package com.mio.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CharacterInteractionRepository extends JpaRepository<CharacterInteraction, UUID> {

    Optional<CharacterInteraction> findByUserIdAndCharacterId(UUID userId, String characterId);
}
