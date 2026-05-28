package com.mio.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "잘못된 입력입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 오류가 발생했습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다."),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "중복된 요청입니다."),

    // Auth
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_INVALID", "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_EXPIRED", "만료된 토큰입니다."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID", "유효하지 않은 Refresh Token입니다."),
    OAUTH_FAILED(HttpStatus.UNAUTHORIZED, "OAUTH_VERIFICATION_FAILED", "소셜 로그인에 실패했습니다."),

    // User
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."),
    USER_SUSPENDED(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", "이용이 제한된 계정입니다."),
    USER_WITHDRAWN(HttpStatus.GONE, "GONE", "탈퇴한 계정입니다."),
    ONBOARDING_REQUIRED(HttpStatus.FORBIDDEN, "ONBOARDING_REQUIRED", "온보딩을 먼저 완료해야 합니다."),

    // Onboarding
    ONBOARDING_STEP_NOT_COMPLETED(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION", "이전 온보딩 단계를 완료해야 합니다."),
    INVALID_CHARACTER_ID(HttpStatus.BAD_REQUEST, "INVALID_CHARACTER_ID", "유효하지 않은 캐릭터 ID입니다."),
    INVALID_EMOTION_STATE(HttpStatus.BAD_REQUEST, "INVALID_EMOTION_STATE", "유효하지 않은 감정 상태입니다."),
    INVALID_CONCERN_TYPE(HttpStatus.BAD_REQUEST, "INVALID_CONCERN_TYPE", "유효하지 않은 고민 유형입니다."),
    INVALID_PREFERRED_STYLE(HttpStatus.BAD_REQUEST, "INVALID_PREFERRED_STYLE", "유효하지 않은 상담 스타일입니다."),

    // Session
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "세션을 찾을 수 없습니다."),
    SESSION_ALREADY_ACTIVE(HttpStatus.CONFLICT, "SESSION_ALREADY_ACTIVE", "이미 진행 중인 활성 세션이 있습니다."),
    SESSION_ALREADY_ENDED(HttpStatus.GONE, "GONE", "이미 종료된 세션입니다."),
    LOCKED_BY_SAFETY(HttpStatus.LOCKED, "LOCKED_BY_SAFETY", "보안 정책에 의해 차단된 요청입니다."),

    // Daily Test
    DAILY_TEST_NOT_FOUND(HttpStatus.NOT_FOUND, "DAILY_TEST_NOT_FOUND", "오늘의 데일리 테스트가 없습니다."),
    DAILY_TEST_ALREADY_COMPLETED(HttpStatus.CONFLICT, "DAILY_TEST_ALREADY_COMPLETED", "이미 완료한 데일리 테스트입니다."),
    INVALID_DAILY_TEST_CONTENT(HttpStatus.BAD_REQUEST, "INVALID_DAILY_TEST_CONTENT", "데일리 테스트 콘텐츠가 올바르지 않습니다."),

    // Todo
    TODO_NOT_FOUND(HttpStatus.NOT_FOUND, "TODO_NOT_FOUND", "존재하지 않는 할 일입니다."),
    TODO_ALREADY_COMPLETED(HttpStatus.CONFLICT, "TODO_ALREADY_COMPLETED", "이미 완료 또는 처리된 할 일입니다."),
    TODO_ALREADY_GENERATED(HttpStatus.CONFLICT, "TODO_ALREADY_GENERATED", "이미 생성된 오늘의 할 일입니다."),
    TODO_EXPIRED(HttpStatus.UNPROCESSABLE_ENTITY, "TODO_EXPIRED", "만료된 할 일은 처리할 수 없습니다."),

    // Notification
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "알림 설정을 찾을 수 없습니다."),
    DEVICE_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "DEVICE_TOKEN_NOT_FOUND", "디바이스 토큰을 찾을 수 없습니다."),

    // Rate Limit
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청 한도를 초과했습니다."),

    // External
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", "외부 서비스가 일시적으로 응답하지 않습니다."),
    UPSTREAM_BAD_GATEWAY(HttpStatus.BAD_GATEWAY, "UPSTREAM_BAD_GATEWAY", "외부 서비스로부터 잘못된 응답을 받았습니다."),
    PROVIDER_MISMATCH(HttpStatus.CONFLICT, "PROVIDER_MISMATCH", "동일 이메일로 다른 소셜 계정이 이미 존재합니다."),
    INVALID_PROVIDER(HttpStatus.BAD_REQUEST, "INVALID_PROVIDER", "지원하지 않는 소셜 로그인 제공자입니다."),
    SIGNUP_STEP_INVALID(HttpStatus.FORBIDDEN, "SIGNUP_STEP_INVALID", "현재 가입 단계에서 허용되지 않는 요청입니다."),
    NICKNAME_DUPLICATE(HttpStatus.CONFLICT, "CONFLICT", "이미 사용 중인 닉네임입니다."),
    CONSENT_REQUIRED(HttpStatus.BAD_REQUEST, "CONSENT_REQUIRED", "필수 약관에 동의해야 합니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
