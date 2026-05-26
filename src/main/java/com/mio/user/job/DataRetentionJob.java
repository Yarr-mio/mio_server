package com.mio.user.job;

import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataRetentionJob {

    private static final int RETENTION_DAYS = 30;

    private final UserRepository userRepository;

    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    @Transactional
    public void hardDeleteExpiredUsers() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(RETENTION_DAYS);
        List<User> targets = userRepository.findAllByStatusAndDeletedAtBefore("DELETED", cutoff);

        if (targets.isEmpty()) {
            return;
        }

        userRepository.deleteAll(targets);
        log.info("[DataRetentionJob] 하드 삭제 완료: {}명 (기준일: {})", targets.size(), cutoff);
    }
}
