package com.mio.ai.memory.episodic;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserBeliefRepository extends JpaRepository<UserBelief, UUID> {

    @Query("""
            SELECT b FROM UserBelief b
            WHERE b.user.id = :userId AND b.status = 'active'
            ORDER BY b.confidence DESC
            LIMIT 5
            """)
    List<UserBelief> findActiveTop5(UUID userId);

    List<UserBelief> findByUser_IdAndStatus(UUID userId, String status);

    Optional<UserBelief> findByUser_IdAndStatusAndBeliefIdentityVersionAndBeliefIdentityHash(
            UUID userId, String status, short beliefIdentityVersion, byte[] beliefIdentityHash);

    @Modifying
    @Query(value = """
            INSERT INTO user_beliefs (
                user_id, belief_text_ciphertext, belief_text_dek_id, belief_kind, polarity,
                belief_identity_hash, belief_identity_version
            ) VALUES (
                :userId, :ciphertext, :dekId, :beliefKind, :polarity,
                :identityHash, :identityVersion
            )
            ON CONFLICT (user_id, belief_identity_version, belief_identity_hash)
            WHERE status = 'active' AND belief_identity_hash IS NOT NULL
            DO NOTHING
            """, nativeQuery = true)
    int insertActiveSemanticBeliefIfAbsent(
            @Param("userId") UUID userId,
            @Param("ciphertext") byte[] ciphertext,
            @Param("dekId") String dekId,
            @Param("beliefKind") String beliefKind,
            @Param("polarity") String polarity,
            @Param("identityHash") byte[] identityHash,
            @Param("identityVersion") short identityVersion);
}
