package com.mio.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 기동 로그가 스케줄링 활성 여부를 실제로 구분해 남기는지 확인한다 (이슈 #402).
 *
 * <p>배포 직후 로그만 보고 "스케줄러 살아있음"을 판정해야 하므로, 두 갈래가 각각 다른 레벨로
 * 찍히는 것이 이 로그의 존재 이유다.
 */
class SchedulingStartupLoggerTest {

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        Logger logger = (Logger) LoggerFactory.getLogger(SchedulingStartupLogger.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(SchedulingStartupLogger.class)).detachAppender(appender);
    }

    @Test
    @DisplayName("스케줄러가 등록돼 있으면 등록 태스크 수와 함께 INFO 로 남긴다")
    void logsEnabledStateAtInfo() {
        new SchedulingStartupLogger(providerOf(new ScheduledAnnotationBeanPostProcessor()))
                .logSchedulingState();

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains("[Scheduling] ENABLED");
    }

    @Test
    @DisplayName("스케줄러가 없으면 WARN 으로 남겨 프로덕션에서 눈에 띄게 한다")
    void logsDisabledStateAtWarn() {
        new SchedulingStartupLogger(providerOf(null)).logSchedulingState();

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel())
                .as("프로덕션에서 스케줄링 비활성은 비정상이므로 INFO 로 묻히면 안 된다")
                .isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("[Scheduling] DISABLED");
    }

    private ILoggingEvent onlyEvent() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ScheduledAnnotationBeanPostProcessor> providerOf(
            ScheduledAnnotationBeanPostProcessor processor) {
        ObjectProvider<ScheduledAnnotationBeanPostProcessor> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(processor);
        return provider;
    }
}
