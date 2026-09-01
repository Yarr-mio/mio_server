package com.mio.notification.repository;

import com.mio.notification.domain.NotificationSetting;
import com.mio.support.MioIntegrationTest;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스케줄 발송 대상 조회가 탈퇴·정지 유저를 실제 DB에서 걸러내는지 검증한다 (이슈 #388).
 *
 * <p>목으로는 확인할 수 없다. 필터는 전적으로 JPQL 조인 조건에 들어 있어서, 조건이 빠지거나
 * 컬럼 매핑이 어긋나도 서비스 단위 테스트는 그대로 통과한다. 탈퇴자에게 푸시가 나가는 것은
 * 개인정보 처리 정지 위반이므로 쿼리 자체를 실행해서 확인한다.
 */
@MioIntegrationTest
class NotificationSettingRepositoryIntegrationTest {

    @Autowired private NotificationSettingRepository notificationSettingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM notification_settings WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }

    @Test
    @DisplayName("탈퇴한 유저의 알림 설정은 발송 대상에서 제외된다")
    void findSendableTargets_excludesWithdrawnUser() {
        UUID activeUserId = createUserWithSetting(user -> user.activate());
        UUID withdrawnUserId = createUserWithSetting(user -> user.softDelete("anonymized-" + UUID.randomUUID()));

        List<UUID> targetUserIds = sendableTargetUserIds();

        assertThat(targetUserIds).contains(activeUserId);
        assertThat(targetUserIds).doesNotContain(withdrawnUserId);
    }

    @Test
    @DisplayName("정지된 유저의 알림 설정은 발송 대상에서 제외된다")
    void findSendableTargets_excludesSuspendedUser() {
        UUID suspendedUserId = createUserWithSetting(user -> user.activate());
        jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", suspendedUserId);

        assertThat(sendableTargetUserIds()).doesNotContain(suspendedUserId);
    }

    @Test
    @DisplayName("status가 남아 있어도 deleted_at이 찍힌 유저는 발송 대상에서 제외된다")
    void findSendableTargets_excludesUserWithDeletedAt() {
        UUID userId = createUserWithSetting(user -> user.activate());
        jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?", userId);

        assertThat(sendableTargetUserIds()).doesNotContain(userId);
    }

    @Test
    @DisplayName("알림에 동의하지 않은 유저는 발송 대상에서 제외된다")
    void findSendableTargets_excludesUserWithoutAgreement() {
        UUID userId = createUserWithSetting(user -> user.activate());
        jdbcTemplate.update("UPDATE notification_settings SET notification_agree = false WHERE user_id = ?", userId);

        assertThat(sendableTargetUserIds()).doesNotContain(userId);
    }

    /** 로컬 DB에는 다른 테스트가 남긴 유저도 있으므로 전체 페이지를 훑어 모은다. */
    private List<UUID> sendableTargetUserIds() {
        List<UUID> userIds = new ArrayList<>();
        Pageable pageable = PageRequest.of(0, 200, Sort.by(Sort.Direction.ASC, "id"));
        Slice<NotificationSetting> slice;
        do {
            slice = notificationSettingRepository.findSendableTargets(pageable);
            // user 가 즉시 로딩되지 않으면(EntityGraph 누락) 여기서 LazyInitializationException 이 난다.
            slice.getContent().forEach(setting -> userIds.add(setting.getUser().getId()));
            pageable = slice.hasNext() ? slice.nextPageable() : Pageable.unpaged();
        } while (slice.hasNext());
        return userIds;
    }

    private UUID createUserWithSetting(java.util.function.Consumer<User> mutation) {
        User user = User.builder()
                .socialProvider("kakao")
                .socialId("notification-target-it-" + UUID.randomUUID())
                .privacyConsent(true)
                .build();
        mutation.accept(user);
        User saved = userRepository.saveAndFlush(user);
        createdUserIds.add(saved.getId());

        notificationSettingRepository.saveAndFlush(NotificationSetting.builder().user(saved).build());
        return saved.getId();
    }
}
