package com.mio.user.repository;

import com.mio.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findBySocialProviderAndSocialId(String socialProvider, String socialId);

    Optional<User> findByEmailAndSocialProviderNot(String email, String socialProvider);

    boolean existsByNickname(String nickname);

    boolean existsByNicknameAndIdNot(String nickname, UUID id);

    List<User> findAllByStatusAndDeletedAtBefore(String status, OffsetDateTime cutoff);
}