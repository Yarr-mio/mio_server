package com.mio.auth.redis;

import com.mio.user.domain.SignupStep;

// refresh:{uuid} 에 저장되는 값 — 토큰 갱신 시 DB 조회 없이 새 JWT를 발급하기 위한 최소 정보
public record RefreshTokenInfo(
        String userId,
        String deviceId,
        String socialProvider,
        SignupStep signupStep  // JWT claim 재구성 및 가입 단계 복원에 사용
) {
}