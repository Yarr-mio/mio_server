package com.mio.domain.user;

/**
 * @deprecated Refresh Token은 Redis로 이관되었습니다.
 * DB 테이블(user_refresh_tokens)은 삭제 예정.
 * Redis 키 패턴: refresh:{uuid} / refresh:user:{user_id}
 * @see com.mio.infra.redis (RedisRepository 구현 예정)
 */
@Deprecated(since = "v2.4", forRemoval = true)
public final class UserRefreshToken {
    private UserRefreshToken() {}
}
