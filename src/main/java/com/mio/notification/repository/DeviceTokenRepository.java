package com.mio.notification.repository;

import com.mio.notification.domain.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    List<DeviceToken> findByUser_IdAndIsValidTrue(UUID userId);

    Optional<DeviceToken> findByUser_IdAndDeviceId(UUID userId, String deviceId);

    Optional<DeviceToken> findByUser_IdAndToken(UUID userId, String token);

    /**
     * 등록하려는 (deviceId, token) 과 충돌하는 <b>타 행</b>의 유효 토큰을 찾는다 (이슈 #391).
     *
     * <p>같은 물리 기기를 여러 계정이 돌려 쓰면 device_id 나 token 이 겹친 채로 여러 행이
     * 동시에 유효해져 알림이 다른 사람 기기로 배달된다. 등록 대상 본인 행
     * ({@code userId} + {@code deviceId} 가 모두 일치)만 제외하고 나머지를 모두 회수 대상으로 본다.
     */
    @Query("""
            select d from DeviceToken d
            where d.isValid = true
              and (d.deviceId = :deviceId or d.token = :token)
              and (d.user.id <> :userId or d.deviceId <> :deviceId)
            """)
    List<DeviceToken> findValidConflicts(@Param("userId") UUID userId,
                                         @Param("deviceId") String deviceId,
                                         @Param("token") String token);

    /**
     * 토큰을 등록한 적은 있으나 현재 유효 토큰이 0개인 <b>발송 대상 유저</b>를 집계한다 (이슈 #392).
     *
     * <p>APNs 400/410 응답으로 토큰이 무효화된 뒤 앱이 재등록을 호출하지 않으면 그 유저의 발송은
     * 전부 유령 SENT 가 된다. 무효화 자체는 정상 동작이므로 없애지 않고, 끊긴 유저를 관측 가능하게 만든다.
     *
     * <p>탈퇴·정지 유저는 애초에 발송 대상이 아니므로 목록에서 제외한다. 판정 기준은 스케줄 발송 대상
     * 조회({@code NotificationSettingRepository#findSendableTargets}, 이슈 #388)와 동일하게
     * {@code deleted_at IS NULL AND status NOT IN ('DELETED', 'SUSPENDED')} 를 쓴다. 두 곳이 다른
     * 기준을 쓰면 "발송은 되는데 목록에는 없는" 유저가 생겨 관측 자체를 신뢰할 수 없게 된다.
     */
    @Query("""
            select u.id as userId,
                   max(d.updatedAt) as lastTokenUpdatedAt,
                   sum(case when d.isValid = false then 1 else 0 end) as invalidTokenCount
            from DeviceToken d
            join d.user u
            where u.deletedAt is null
              and u.status not in ('DELETED', 'SUSPENDED')
            group by u.id
            having sum(case when d.isValid = true then 1 else 0 end) = 0
            order by max(d.updatedAt) desc
            """)
    List<UserWithoutValidToken> findUsersWithoutValidToken();

    /** {@link #findUsersWithoutValidToken()} 결과 projection. */
    interface UserWithoutValidToken {
        UUID getUserId();

        OffsetDateTime getLastTokenUpdatedAt();

        long getInvalidTokenCount();
    }
}
