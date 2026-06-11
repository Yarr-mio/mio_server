package com.mio.ai.repository;

import com.mio.ai.domain.UserSelfModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserSelfModelRepository extends JpaRepository<UserSelfModel, UUID> {}
