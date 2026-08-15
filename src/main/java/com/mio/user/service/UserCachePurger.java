package com.mio.user.service;

import com.mio.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 탈퇴한 사용자의 Redis 캐시를 지운다 (이슈 #373, 로드맵 §12 P0-6).
 *
 * <p>지금까지 Redis 는 탈퇴 시 <b>한 번도 정리되지 않았다.</b> 모든 캐시 키가
 * {@code session:{sessionId}:*} 형태라 사용자 단위 삭제를 표현하는 것 자체가 불가능했고,
 * 그래서 TTL 만료(대화 버퍼 90분, 위기 래치 14일)에만 의존했다. 사용자가 지워 달라고
 * 했는데 대화 원문이 캐시에 최대 90분, 위기 흔적이 최대 14일 남아 있었다는 뜻이다.
 *
 * <p><b>키 스킴은 바꾸지 않는다.</b> 사용자의 세션 ID 목록은 DB 에 있으므로, 그 목록으로
 * {@code session:{id}:*} 를 지우면 사용자 범위 purge 가 표현 가능해진다. 키에 userId 를
 * 넣는 재설계는 진행 중인 세션에 영향을 주므로 별도 과제다.
 *
 * <p><b>{@code KEYS} 를 쓰지 않는다.</b> 운영 Redis 에서 {@code KEYS} 는 전체를 훑는 동안
 * 다른 명령을 막는다. 지울 키를 정확히 알고 있으므로 나열해서 지운다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserCachePurger {

    /**
     * 세션 하나가 남기는 키들.
     *
     * <p>각 소유자가 자기 클래스에 상수로 들고 있어 한곳에 모여 있지 않다. 여기서 다시
     * 적는 대신 소유자를 참조하고 싶지만, 대부분 {@code private} 이라 그럴 수 없다.
     * 새 세션 캐시를 추가하는 사람이 이 목록도 갱신해야 한다는 뜻이므로 이유를 적어 둔다.
     *
     * <ul>
     *   <li>{@code WorkingMemory} — messages(대화 원문), working, beliefs, triggers,
     *       ontology_activation</li>
     *   <li>{@code SafetyProfileBuilder} — safety_profile</li>
     *   <li>{@code ContextPreWarmer} — context_cache</li>
     *   <li>{@code AiCacheKeys} — checkpoint_cache</li>
     * </ul>
     */
    private static final List<String> SESSION_KEY_PATTERNS = List.of(
            "session:%s:messages",
            "session:%s:working",
            "session:%s:beliefs",
            "session:%s:triggers",
            "session:%s:ontology_activation",
            "session:%s:safety_profile",
            "session:%s:context_cache",
            "session:%s:checkpoint_cache"
    );

    /** 사용자 단위 키. {@code CrisisSafetyLatch} 의 미기록 위기 래치(TTL 14일). */
    private static final List<String> USER_KEY_PATTERNS = List.of(
            "user:%s:unrecorded_crisis"
    );

    private final StringRedisTemplate redisTemplate;
    private final SessionRepository sessionRepository;

    /**
     * 사용자의 캐시를 모두 지운다.
     *
     * @return 삭제 요청한 키 수 (실제 존재 여부와 무관)
     * @throws RuntimeException Redis 장애 시. <b>삼키지 않는다</b> — 캐시가 남았는데
     *         삭제 완료로 기록하면 지워지지 않은 개인정보가 지워진 것으로 남는다.
     */
    public int purge(UUID userId) {
        List<UUID> sessionIds = sessionRepository.findAllIdsByUserId(userId);
        List<String> keys = new ArrayList<>();

        for (UUID sessionId : sessionIds) {
            for (String pattern : SESSION_KEY_PATTERNS) {
                keys.add(pattern.formatted(sessionId));
            }
        }
        for (String pattern : USER_KEY_PATTERNS) {
            keys.add(pattern.formatted(userId));
        }

        if (keys.isEmpty()) {
            return 0;
        }
        redisTemplate.delete(keys);
        log.info("UserCachePurger: purged {} cache keys for userId={} across {} sessions",
                keys.size(), userId, sessionIds.size());
        return keys.size();
    }
}
