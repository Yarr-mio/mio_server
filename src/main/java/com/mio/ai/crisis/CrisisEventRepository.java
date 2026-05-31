package com.mio.ai.crisis;

import com.mio.crisis.domain.CrisisEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CrisisEventRepository extends JpaRepository<CrisisEvent, UUID> {
}
