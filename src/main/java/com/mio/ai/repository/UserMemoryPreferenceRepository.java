package com.mio.ai.repository;

import com.mio.ai.domain.UserMemoryPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface UserMemoryPreferenceRepository extends JpaRepository<UserMemoryPreference, UUID> {

    Optional<UserMemoryPreference> findByUserId(UUID userId);

    @Modifying
    @Query(value = "UPDATE user_memory_preferences SET disliked_patterns = :patterns::jsonb WHERE user_id = :userId",
            nativeQuery = true)
    void updateDislikedPatterns(UUID userId, String patterns);
}
