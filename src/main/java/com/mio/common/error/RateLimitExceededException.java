package com.mio.common.error;

import lombok.Getter;

/**
 * 이슈 #328 — 공용 {@code BusinessException(RATE_LIMIT_EXCEEDED)}를 던지는 도메인
 * (체크인/세션 메시지)은 여럿인데, 이벤트 배치만 Retry-After 헤더가 필요해서 서브클래스로
 * 분리했다. {@link GlobalExceptionHandler}가 이 타입을 BusinessException보다 더
 * 구체적인 핸들러로 잡아 헤더를 붙인다 — 다른 도메인의 429 응답 형태는 그대로 유지된다.
 */
@Getter
public class RateLimitExceededException extends BusinessException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
