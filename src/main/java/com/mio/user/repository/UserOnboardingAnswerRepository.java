package com.mio.user.repository;

import com.mio.user.domain.UserOnboardingAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserOnboardingAnswerRepository extends JpaRepository<UserOnboardingAnswer, UUID> {

    Optional<UserOnboardingAnswer> findByUser_Id(UUID userId);
}
