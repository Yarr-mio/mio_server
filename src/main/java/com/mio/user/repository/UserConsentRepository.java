package com.mio.user.repository;

import com.mio.user.domain.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserConsentRepository extends JpaRepository<UserConsent, UUID> {
}