package com.mio.ai.crisis;

import com.mio.crisis.domain.CrisisEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CrisisEventRepository extends JpaRepository<CrisisEvent, UUID> {

    /** 재시도가 같은 논리 이벤트를 다시 만들지 않도록 기존 행을 찾는다 (이슈 #269). */
    Optional<CrisisEvent> findByDedupKey(UUID dedupKey);
}
