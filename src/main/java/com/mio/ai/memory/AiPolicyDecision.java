package com.mio.ai.memory;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ai_policy_decisions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AiPolicyDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "decision_id", nullable = false, unique = true)
    private String decisionId;

    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Column(name = "security_level")
    private String securityLevel;

    @Column(name = "risk_level")
    private String riskLevel;

    @Column(name = "generation_mode")
    private String generationMode;

    @Column(name = "delivery_mode")
    private String deliveryMode;

    @Column(name = "action")
    private String action;

    @Column(name = "require_output_guard", nullable = false)
    private boolean requireOutputGuard;

    @Column(name = "trace", nullable = false, columnDefinition = "jsonb")
    private String trace;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
