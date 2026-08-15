package com.mio.ai.cost;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InfraCostAllocationSensitivityRepository
        extends JpaRepository<InfraCostAllocationSensitivity, UUID> {
}
