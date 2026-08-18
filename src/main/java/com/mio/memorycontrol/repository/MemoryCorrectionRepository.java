package com.mio.memorycontrol.repository;

import com.mio.memorycontrol.domain.MemoryCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MemoryCorrectionRepository extends JpaRepository<MemoryCorrection, UUID> {

    /** 최신 정정이 먼저 오도록 정렬 — 목록 조합 시 기억별 첫 항목이 최신 정정문이다. */
    List<MemoryCorrection> findByUserIdAndMemoryIdInOrderByCreatedAtDesc(
            UUID userId, Collection<UUID> memoryIds);
}
