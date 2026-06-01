package com.mio.ai.repository;

import com.mio.ai.domain.AiPolicyDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AiPolicyDecisionRepository extends JpaRepository<AiPolicyDecision, UUID> {
}
