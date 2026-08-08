package com.mio.notification.service;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.notification.domain.DeviceToken;
import com.mio.notification.dto.DeviceTokenRegisterRequest;
import com.mio.notification.dto.DeviceTokenResponse;
import com.mio.notification.dto.StaleDeviceTokenUserResponse;
import com.mio.notification.repository.DeviceTokenRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    /** 경합 재시도 횟수. 충돌 상대가 커밋을 끝내면 다음 시도에서 그 행을 보고 회수한다. */
    private static final int MAX_REGISTER_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 20L;

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;

    /**
     * 디바이스 토큰을 등록하거나 갱신한다.
     *
     * <p>한 물리 기기에는 유효한 토큰 행이 하나만 존재해야 한다 (이슈 #391). 같은 기기를 여러 계정이
     * 돌려 쓰면 이전 계정 행이 유효한 채로 남아 그 계정의 알림이 지금 로그인한 사람 기기로 배달된다.
     * 그래서 본인 행을 갱신하기 전에 device_id 나 token 이 겹치는 타 행을 먼저 무효화한다.
     *
     * <p>두 계정이 거의 동시에 등록하면 서로의 미커밋 행을 볼 수 없어 나중에 커밋하는 쪽이 부분 유니크
     * 인덱스를 위반한다. 이때 500 으로 새어 등록이 유실되지 않도록, 트랜잭션 경계를 메서드 밖으로 꺼내
     * <b>짧은 backoff 후 새 트랜잭션에서 재시도</b>한다. 재시도는 이미 커밋된 상대 행을 보고 정상적으로
     * 회수한다. 끝내 실패하면 재시도 가능한 409 로 응답한다.
     *
     * <p>기존 행이 무효화된 상태였다면 {@link DeviceToken#refreshToken} 이 다시 유효로 되돌린다.
     * APNs 거절로 끊긴 유저의 유일한 복구 경로이므로 (이슈 #392) 이 동작을 유지한다.
     */
    public DeviceTokenResponse register(UUID userId, DeviceTokenRegisterRequest request) {
        for (int attempt = 1; attempt <= MAX_REGISTER_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> registerInTransaction(userId, request));
            } catch (DataIntegrityViolationException e) {
                log.warn("Device token registration raced (attempt {}/{}): deviceId={} token={}",
                        attempt, MAX_REGISTER_ATTEMPTS, request.deviceId(), maskToken(request.pushToken()));
                if (attempt < MAX_REGISTER_ATTEMPTS) {
                    backoff(attempt);
                }
            }
        }
        throw new BusinessException(ErrorCode.DEVICE_TOKEN_REGISTER_CONFLICT);
    }

    /**
     * 로그아웃 등으로 본인 토큰을 해제한다.
     *
     * <p>조회를 {@code userId} 로 한정하므로 같은 기기에 남아 있는 다른 계정 행에는 영향을 주지 않는다.
     * 행을 지우지 않고 무효화만 하는 이유는 재로그인 시 같은 행을 다시 유효화해 이력을 유지하기 위해서다.
     */
    @Transactional
    public void deleteByToken(UUID userId, String rawToken) {
        DeviceToken token = deviceTokenRepository.findByUser_IdAndToken(userId, rawToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEVICE_TOKEN_NOT_FOUND));
        token.invalidate();
    }

    /**
     * 토큰을 등록한 적은 있으나 현재 유효 토큰이 0개인 <b>발송 대상 유저</b>를 조회한다 (이슈 #392).
     *
     * <p>APNs 거절로 무효화된 뒤 재등록이 없어 알림이 끊긴 유저를 식별하는 용도다.
     */
    @Transactional(readOnly = true)
    public List<StaleDeviceTokenUserResponse> findUsersWithoutValidToken() {
        return deviceTokenRepository.findUsersWithoutValidToken().stream()
                .map(StaleDeviceTokenUserResponse::from)
                .toList();
    }

    private DeviceTokenResponse registerInTransaction(UUID userId, DeviceTokenRegisterRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        invalidateConflicts(userId, request);

        DeviceToken token = deviceTokenRepository.findByUser_IdAndDeviceId(userId, request.deviceId())
                .map(existing -> {
                    existing.refreshToken(request.pushToken(), request.appVersion());
                    return existing;
                })
                .orElseGet(() -> deviceTokenRepository.save(
                        DeviceToken.builder()
                                .user(user)
                                .deviceId(request.deviceId())
                                .platform(request.platform())
                                .token(request.pushToken())
                                .appVersion(request.appVersion())
                                .build()
                ));

        // 커밋 시점이 아니라 여기서 제약 위반이 드러나야 재시도 루프가 그 예외를 잡을 수 있다.
        deviceTokenRepository.flush();

        return DeviceTokenResponse.from(token);
    }

    private void invalidateConflicts(UUID userId, DeviceTokenRegisterRequest request) {
        List<DeviceToken> conflicts =
                deviceTokenRepository.findValidConflicts(userId, request.deviceId(), request.pushToken());
        if (conflicts.isEmpty()) {
            return;
        }

        conflicts.forEach(DeviceToken::invalidate);
        // 부분 유니크 인덱스(ux_device_tokens_token_valid / _device_id_valid) 위반을 피하려면
        // 본인 행을 유효화하기 전에 회수 UPDATE 가 먼저 DB 에 반영돼야 한다.
        deviceTokenRepository.flush();

        log.info("Reclaimed {} conflicting device token(s) for deviceId={} token={}",
                conflicts.size(), request.deviceId(), maskToken(request.pushToken()));
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.DEVICE_TOKEN_REGISTER_CONFLICT);
        }
    }

    private String maskToken(String token) {
        if (token == null || token.length() <= 8) {
            return "***";
        }
        return token.substring(0, 8) + "...";
    }
}
