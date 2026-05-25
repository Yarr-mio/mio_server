package com.mio.user.repository;

import com.mio.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findBySocialProviderAndSocialId(String socialProvider, String socialId);

    Optional<User> findByEmailAndSocialProviderNot(String email, String socialProvider);

    boolean existsByNickname(String nickname);
}