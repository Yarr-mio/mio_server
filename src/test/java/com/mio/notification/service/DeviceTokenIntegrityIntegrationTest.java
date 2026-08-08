package com.mio.notification.service;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.notification.dto.DeviceTokenRegisterRequest;
import com.mio.notification.dto.StaleDeviceTokenUserResponse;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 디바이스 토큰 정합성의 실제 DB 동작 검증 (이슈 #391, #392).
 *
 * <p>목으로는 확인할 수 없는 세 가지를 잡는다.
 *
 * <ul>
 *   <li>V54 마이그레이션이 추가한 부분 유니크 인덱스는 "유효 토큰 1개 = 물리 기기 1대" 불변식을
 *       DB 레벨에서 강제한다. 서비스 코드가 회수 UPDATE 를 먼저 flush 하지 않으면 등록이 제약
 *       위반으로 터진다 — 이 순서는 실제 DB 없이는 검증되지 않는다.</li>
 *   <li>프로덕션에는 이미 중복 행이 있으므로, 제약을 걸기 전 정리 UPDATE 가 빠지면 배포 시
 *       마이그레이션 자체가 실패한다. 정리 SQL 을 마이그레이션 파일에서 그대로 읽어 복제 테이블에
 *       돌려 본다.</li>
 *   <li>유효 토큰 0개 유저 집계는 JPQL {@code having} 절이라 문법이 어긋나도 컴파일은 통과한다.</li>
 * </ul>
 */
@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("integration-test")
class DeviceTokenIntegrityIntegrationTest {

    private static final String MIGRATION_PATH = "db/migration/V54__dedupe_device_tokens.sql";
    private static final String PROBE_TABLE = "device_tokens_probe";

    @Autowired private DeviceTokenService deviceTokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        userA = createUser();
        userB = createUser();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        List.of(userA, userB).forEach(user -> {
            jdbcTemplate.update("DELETE FROM device_tokens WHERE user_id = ?", user.getId());
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());
        });
    }

    @Test
    @DisplayName("같은 기기에서 계정을 전환하면 유효한 토큰 행은 마지막 계정 하나만 남는다")
    void register_accountSwitchOnSameDevice_leavesSingleValidRow() {
        String deviceId = "device-" + UUID.randomUUID();
        String pushToken = "apns-" + UUID.randomUUID();

        deviceTokenService.register(userA.getId(),
                new DeviceTokenRegisterRequest(deviceId, pushToken, "ios", "1.2.0"));
        deviceTokenService.register(userB.getId(),
                new DeviceTokenRegisterRequest(deviceId, pushToken, "ios", "1.2.0"));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id, is_valid FROM device_tokens WHERE device_id = ?", deviceId);

        assertThat(rows).hasSize(2);
        assertThat(rows).filteredOn(row -> (boolean) row.get("is_valid"))
                .singleElement()
                .satisfies(row -> assertThat(row.get("user_id")).isEqualTo(userB.getId()));
    }

    @Test
    @DisplayName("기기가 새 토큰을 발급받아도 같은 기기의 옛 유효 토큰은 회수된다")
    void register_rotatedTokenOnSameDevice_reclaimsPreviousRow() {
        String deviceId = "device-" + UUID.randomUUID();

        deviceTokenService.register(userA.getId(),
                new DeviceTokenRegisterRequest(deviceId, "apns-old-" + UUID.randomUUID(), "ios", "1.2.0"));
        deviceTokenService.register(userB.getId(),
                new DeviceTokenRegisterRequest(deviceId, "apns-new-" + UUID.randomUUID(), "ios", "1.2.0"));

        Long validCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM device_tokens WHERE device_id = ? AND is_valid", Long.class, deviceId);

        assertThat(validCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("APNs 거절로 무효화된 토큰은 재등록하면 같은 행이 다시 유효해진다")
    void register_afterInvalidation_revivesSameRow() {
        String deviceId = "device-" + UUID.randomUUID();
        String pushToken = "apns-" + UUID.randomUUID();

        deviceTokenService.register(userA.getId(),
                new DeviceTokenRegisterRequest(deviceId, pushToken, "ios", "1.2.0"));
        UUID rowId = jdbcTemplate.queryForObject(
                "SELECT id FROM device_tokens WHERE device_id = ?", UUID.class, deviceId);

        // APNs 410 Unregistered 로 무효화된 상태를 재현한다.
        jdbcTemplate.update("UPDATE device_tokens SET is_valid = false WHERE id = ?", rowId);

        deviceTokenService.register(userA.getId(),
                new DeviceTokenRegisterRequest(deviceId, pushToken, "ios", "1.3.0"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, is_valid, app_version FROM device_tokens WHERE device_id = ?", deviceId);
        assertThat(row.get("id")).isEqualTo(rowId);
        assertThat(row.get("is_valid")).isEqualTo(true);
        assertThat(row.get("app_version")).isEqualTo("1.3.0");
    }

    @Test
    @DisplayName("부분 유니크 인덱스가 동일 토큰의 중복 유효 행을 DB에서 차단한다")
    void partialUniqueIndex_rejectsDuplicateValidToken() {
        String pushToken = "apns-" + UUID.randomUUID();
        deviceTokenService.register(userA.getId(),
                new DeviceTokenRegisterRequest("device-" + UUID.randomUUID(), pushToken, "ios", "1.2.0"));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO device_tokens (id, user_id, device_id, platform, token, is_valid)
                        VALUES (gen_random_uuid(), ?, ?, 'ios', ?, true)
                        """,
                userB.getId(), "device-" + UUID.randomUUID(), pushToken))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("한 유저가 기기 2대에 등록하면 둘 다 유효하게 유지된다")
    void register_sameUserTwoDevices_bothStayValid() {
        String deviceOne = "device-" + UUID.randomUUID();
        String deviceTwo = "device-" + UUID.randomUUID();

        deviceTokenService.register(userA.getId(),
                new DeviceTokenRegisterRequest(deviceOne, "apns-" + UUID.randomUUID(), "ios", "1.2.0"));
        deviceTokenService.register(userA.getId(),
                new DeviceTokenRegisterRequest(deviceTwo, "apns-" + UUID.randomUUID(), "android", "1.2.0"));

        Long validCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM device_tokens WHERE user_id = ? AND is_valid", Long.class, userA.getId());

        assertThat(validCount).isEqualTo(2L);
    }

    /**
     * 두 계정이 같은 기기 토큰을 동시에 등록하는 경합. 서로의 미커밋 행을 볼 수 없어 나중에 커밋하는
     * 쪽이 부분 유니크 인덱스를 위반한다. 재시도 없이는 그 요청이 500 으로 새면서 등록이 유실된다.
     */
    @Test
    @DisplayName("두 계정이 같은 기기 토큰을 동시에 등록해도 500 없이 유효 행이 하나만 남는다")
    void register_concurrentSameDevice_doesNotLeak500() throws Exception {
        String deviceId = "device-" + UUID.randomUUID();
        String pushToken = "apns-" + UUID.randomUUID();
        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            List<Future<Throwable>> results = List.of(userA, userB).stream()
                    .map(user -> pool.submit(() -> {
                        try {
                            startLine.await(5, TimeUnit.SECONDS);
                            deviceTokenService.register(user.getId(),
                                    new DeviceTokenRegisterRequest(deviceId, pushToken, "ios", "1.2.0"));
                            return (Throwable) null;
                        } catch (Throwable t) {
                            return t;
                        }
                    }))
                    .toList();

            for (Future<Throwable> result : results) {
                Throwable failure = result.get(20, TimeUnit.SECONDS);
                // 경합에서 밀렸다면 재시도 가능한 409 여야 하고, 500 으로 새는 원시 예외는 허용하지 않는다.
                if (failure != null) {
                    assertThat(failure).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) failure).getErrorCode())
                            .isEqualTo(ErrorCode.DEVICE_TOKEN_REGISTER_CONFLICT);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        Long validCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM device_tokens WHERE device_id = ? AND is_valid", Long.class, deviceId);
        assertThat(validCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("탈퇴한 유저는 유효 토큰이 0개여도 조회 대상에서 빠진다")
    void findUsersWithoutValidToken_excludesWithdrawnUsers() {
        deviceTokenService.register(userA.getId(),
                new DeviceTokenRegisterRequest("device-" + UUID.randomUUID(),
                        "apns-" + UUID.randomUUID(), "ios", "1.2.0"));
        jdbcTemplate.update("UPDATE device_tokens SET is_valid = false WHERE user_id = ?", userA.getId());
        jdbcTemplate.update(
                "UPDATE users SET status = 'DELETED', deleted_at = now() WHERE id = ?", userA.getId());

        List<StaleDeviceTokenUserResponse> stale = deviceTokenService.findUsersWithoutValidToken();

        assertThat(stale).extracting(StaleDeviceTokenUserResponse::userId).doesNotContain(userA.getId());
    }

    @Test
    @DisplayName("유효 토큰이 0개인 유저를 조회할 수 있다")
    void findUsersWithoutValidToken_returnsUsersWithOnlyInvalidTokens() {
        deviceTokenService.register(userA.getId(),
                new DeviceTokenRegisterRequest("device-" + UUID.randomUUID(),
                        "apns-" + UUID.randomUUID(), "ios", "1.2.0"));
        deviceTokenService.register(userB.getId(),
                new DeviceTokenRegisterRequest("device-" + UUID.randomUUID(),
                        "apns-" + UUID.randomUUID(), "ios", "1.2.0"));
        jdbcTemplate.update("UPDATE device_tokens SET is_valid = false WHERE user_id = ?", userA.getId());

        List<StaleDeviceTokenUserResponse> stale = deviceTokenService.findUsersWithoutValidToken();

        assertThat(stale).extracting(StaleDeviceTokenUserResponse::userId).contains(userA.getId());
        assertThat(stale).extracting(StaleDeviceTokenUserResponse::userId).doesNotContain(userB.getId());
        assertThat(stale).filteredOn(row -> row.userId().equals(userA.getId()))
                .singleElement()
                .satisfies(row -> assertThat(row.invalidTokenCount()).isEqualTo(1L));
    }

    /**
     * V54 마이그레이션을 device_tokens 복제 테이블에 그대로 실행해, 기존 중복 데이터에서도 실패하지
     * 않는지(정리 → 제약 추가 순서) 검증한다. 실제 테이블에는 이미 제약이 걸려 중복을 만들 수 없으므로
     * 마이그레이션 이전 상태를 복제 테이블로 재현한다.
     */
    @Test
    @DisplayName("V54 마이그레이션은 중복을 정리한 뒤 제약을 걸어 기존 데이터에서 실패하지 않는다")
    void migration_cleansDuplicatesBeforeAddingConstraint() throws Exception {
        jdbcTemplate.execute(
                "CREATE TABLE " + PROBE_TABLE + " (LIKE device_tokens INCLUDING DEFAULTS)");

        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
        UUID sharedTokenOld = insertProbeRow("dev-1", "tok-shared", true, base.minusHours(2));
        UUID sharedTokenNew = insertProbeRow("dev-2", "tok-shared", true, base.minusHours(1));
        UUID sharedDeviceOld = insertProbeRow("dev-3", "tok-a", true, base.minusHours(3));
        UUID sharedDeviceNew = insertProbeRow("dev-3", "tok-b", true, base.minusHours(1));
        UUID alreadyInvalid = insertProbeRow("dev-4", "tok-c", false, base);
        UUID untouched = insertProbeRow("dev-5", "tok-d", true, base);

        // 정리 UPDATE 와 CREATE UNIQUE INDEX 를 마이그레이션 파일 그대로 실행한다.
        // 정리가 빠졌다면 인덱스 생성에서 unique violation 으로 즉시 실패한다.
        for (String statement : loadMigrationStatements()) {
            jdbcTemplate.execute(statement);
        }

        assertThat(probeValidIds()).containsExactlyInAnyOrder(sharedTokenNew, sharedDeviceNew, untouched);
        assertThat(probeValidIds()).doesNotContain(sharedTokenOld, sharedDeviceOld, alreadyInvalid);
    }

    private List<String> loadMigrationStatements() throws Exception {
        String script;
        try (var input = new ClassPathResource(MIGRATION_PATH).getInputStream()) {
            script = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }
        // 실제 테이블 대신 복제 테이블을 대상으로 돌린다. 인덱스 이름도 함께 치환돼 충돌하지 않는다.
        String rewritten = script.replaceAll("(?m)^\\s*--.*$", "")
                .replace("device_tokens", PROBE_TABLE);
        return Arrays.stream(rewritten.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private UUID insertProbeRow(String deviceId, String token, boolean isValid, OffsetDateTime updatedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + PROBE_TABLE
                        + " (id, user_id, device_id, platform, token, is_valid, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'ios', ?, ?, ?, ?)",
                id, userA.getId(), deviceId, token, isValid, updatedAt, updatedAt);
        return id;
    }

    private List<UUID> probeValidIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM " + PROBE_TABLE + " WHERE is_valid", UUID.class);
    }

    private User createUser() {
        User user = User.builder()
                .socialProvider("kakao")
                .socialId("device-token-it-" + UUID.randomUUID())
                .privacyConsent(true)
                .build();
        user.completeOnboarding("mio");
        return userRepository.save(user);
    }
}
