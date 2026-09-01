package com.mio.todo.service;

import com.mio.common.AppConstants;
import com.mio.support.MioIntegrationTest;
import com.mio.todo.domain.BehaviorTask;
import com.mio.todo.dto.TodoResponse;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고하늘 QA 리포트(2026-08-06) — 채팅 종료 직후 생성된 당일 Todo가 GET /v1/todos에서
 * status: expired로 내려옴. 재현 결과, Postgres 세션 타임존이 UTC라 TIMESTAMPTZ로 저장된
 * KST 값을 다시 읽으면 +00:00으로 정규화된다. KST 00:00~08:59에 생성된 값은 UTC로는
 * "전날"이라 {@code TodoService.isExpired()}의 {@code toLocalDate()}가 하루 전 날짜를
 * 반환해 오늘 생성된 SUGGESTED Todo가 EXPIRED로 잘못 계산됐다.
 *
 * <p>Mockito 기반 {@link TodoServiceTest}는 createdAt을 리플렉션으로 직접 주입해 DB
 * 왕복 자체가 없으므로 이 문제를 못 잡는다 — 실제 Postgres에 저장 후 재조회해야 재현된다.
 */
@MioIntegrationTest
class TodoTimezoneIntegrationTest {

    @Autowired private TodoService todoService;
    @Autowired private UserRepository userRepository;
    @Autowired private BehaviorTaskRepository behaviorTaskRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private UUID userId;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .socialProvider("kakao")
                .socialId("todo-tz-it-" + UUID.randomUUID())
                .privacyConsent(true)
                .build();
        user.completeOnboarding("mio");
        user = userRepository.save(user);
        userId = user.getId();

        BehaviorTask task = behaviorTaskRepository.save(BehaviorTask.builder()
                .user(user)
                .generatedFrom("chat")
                .actionText("아침 심호흡")
                .category("심리_안정")
                .difficulty(1)
                .estimatedMinutes(5)
                .build());

        // @PrePersist가 저장 시점 now()로 덮어쓰므로, KST 새벽 생성 상황은 저장 후 직접
        // created_at을 갱신해서 재현한다 — 오늘 KST 00:30.
        OffsetDateTime earlyMorningKst = LocalDate.now(AppConstants.ZONE)
                .atTime(0, 30)
                .atZone(AppConstants.ZONE)
                .toOffsetDateTime();
        jdbcTemplate.update("UPDATE behavior_tasks SET created_at = ? WHERE id = ?",
                earlyMorningKst, task.getId());
        entityManager.clear();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM behavior_tasks WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("KST 00:00~08:59에 생성된 당일 Todo가 DB 재조회 후에도 expired로 잘못 표시되지 않는다")
    void getTodos_earlyMorningKstTask_notMisreportedAsExpired() {
        List<TodoResponse> result = todoService.getTodos(userId, LocalDate.now(AppConstants.ZONE), null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("suggested");
    }
}
