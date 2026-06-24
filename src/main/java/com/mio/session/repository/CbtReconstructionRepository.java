package com.mio.session.repository;

import com.mio.session.domain.CbtReconstruction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CbtReconstructionRepository extends JpaRepository<CbtReconstruction, UUID> {
}
