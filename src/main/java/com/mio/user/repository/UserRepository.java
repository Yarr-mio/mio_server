package com.mio.user.repository;

import com.mio.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findBySocialProviderAndSocialId(String socialProvider, String socialId);

    /**
     * 이슈 #538 — 탈퇴 계정은 더 이상 이메일을 지우지 않으므로(30일 복구 지원), 탈퇴
     * 계정의 이메일 때문에 새 가입자가 엉뚱하게 PROVIDER_MISMATCH로 막히지 않도록
     * status=DELETED는 이 검사에서 제외한다.
     */
    @Query("SELECT u FROM User u WHERE u.email = :email AND u.socialProvider <> :socialProvider "
            + "AND u.status <> 'DELETED'")
    Optional<User> findByEmailAndSocialProviderNot(@Param("email") String email,
                                                    @Param("socialProvider") String socialProvider);

    /** 이슈 #538 — 탈퇴 계정의 닉네임이 다른 사용자를 영구히 막지 않도록 제외 (이유는 위와 동일). */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u "
            + "WHERE u.nickname = :nickname AND u.status <> 'DELETED'")
    boolean existsByNickname(@Param("nickname") String nickname);

    /** 이슈 #538 — 위와 동일한 이유로 탈퇴 계정 제외 (마이페이지 닉네임 변경용). */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u "
            + "WHERE u.nickname = :nickname AND u.id <> :id AND u.status <> 'DELETED'")
    boolean existsByNicknameAndIdNot(@Param("nickname") String nickname, @Param("id") UUID id);

    List<User> findAllByStatusAndDeletedAtBefore(String status, OffsetDateTime cutoff);
}