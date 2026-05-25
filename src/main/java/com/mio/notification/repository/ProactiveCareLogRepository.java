package com.mio.notification.repository;

import com.mio.notification.domain.ProactiveCareLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProactiveCareLogRepository extends JpaRepository<ProactiveCareLog, UUID> {
}
