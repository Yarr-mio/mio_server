package com.mio.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * 기동 시 스케줄링 활성 여부를 한 줄로 남긴다 (이슈 #402).
 *
 * <p>{@link SchedulingConfig} 가 {@code scheduling.enabled} 라는 전역 킬스위치를 만들었다.
 * {@code docker-compose.prod.yml} 은 {@code env_file: .env} 로 파일을 통째로 읽으므로, 저장소·CI
 * 어디에서도 보이지 않는 곳에서 값이 들어와 {@code @Scheduled} 잡 8개가 <b>조용히</b> 전부 멈출 수 있다.
 * {@code DataRetentionJob} 의 하드 삭제와 리플렉션 잡의 LLM 호출이 걸려 있어 관측 수단이 필요하다.
 *
 * <p>프로퍼티 값이 아니라 {@link ScheduledAnnotationBeanPostProcessor} 의 실제 등록 여부를 본다.
 * 프로퍼티는 "의도"고 이 빈은 "결과"이며, 배포 직후 확인해야 하는 건 결과다.
 * 비활성은 프로덕션에서 비정상이므로 {@code WARN} 으로 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulingStartupLogger {

    private final ObjectProvider<ScheduledAnnotationBeanPostProcessor> scheduledPostProcessor;

    @EventListener(ApplicationReadyEvent.class)
    public void logSchedulingState() {
        ScheduledAnnotationBeanPostProcessor processor = scheduledPostProcessor.getIfAvailable();

        if (processor == null) {
            log.warn("[Scheduling] DISABLED — 스케줄러가 등록되지 않아 @Scheduled 잡이 하나도 실행되지 않는다. "
                    + "프로덕션이라면 비정상이다. scheduling.enabled 설정을 확인하라(기본값 true).");
            return;
        }

        log.info("[Scheduling] ENABLED — 등록된 스케줄 태스크 {}건", processor.getScheduledTasks().size());
    }
}
