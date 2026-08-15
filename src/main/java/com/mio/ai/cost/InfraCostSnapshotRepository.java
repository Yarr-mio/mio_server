package com.mio.ai.cost;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface InfraCostSnapshotRepository extends JpaRepository<InfraCostSnapshot, UUID> {

    Optional<InfraCostSnapshot> findTopByBillingPeriodStartOrderBySnapshotAtDesc(LocalDate billingPeriodStart);
}
