package com.mio.config;

import com.mio.session.job.StaleSummarySweepJob;
import com.mio.support.MioIntegrationTest;
import com.mio.user.job.DataRetentionJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 통합 테스트 컨텍스트에 프로덕션 스케줄러가 등록되지 않는 것을 확인한다 (이슈 #402).
 *
 * <p>설정을 바꾼 것만으로는 "잡이 안 돈다"를 보장했다고 할 수 없어, 스케줄 등록 자체를 담당하는
 * {@link ScheduledAnnotationBeanPostProcessor} 의 부재와 등록된 스케줄 태스크가 0건임을 단언한다.
 * 동시에 잡 빈은 그대로 주입되어야 한다 — {@code StaleSummarySweepIntegrationTest} 처럼
 * {@code run()} 을 직접 부르는 테스트 방식이 계속 동작해야 하기 때문이다.
 */
@MioIntegrationTest
class SchedulingDisabledIntegrationTest {

    @Autowired private ApplicationContext applicationContext;
    @Autowired private Environment environment;
    @Autowired private StaleSummarySweepJob staleSummarySweepJob;
    @Autowired private DataRetentionJob dataRetentionJob;

    @Test
    @DisplayName("테스트 컨텍스트에는 스케줄러 후처리기가 등록되지 않는다")
    void schedulingIsDisabled() {
        assertThat(environment.getProperty("scheduling.enabled")).isEqualTo("false");
        assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class))
                .as("이 빈이 있으면 @Scheduled 잡 8개가 테스트 도중 실제로 실행된다")
                .isEmpty();
        assertThat(applicationContext.getBeanNamesForType(SchedulingConfig.class)).isEmpty();
    }

    @Test
    @DisplayName("등록된 스케줄 태스크가 한 건도 없다")
    void noScheduledTasksAreRegistered() {
        long scheduledTasks = applicationContext.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .mapToLong(holder -> holder.getScheduledTasks().size())
                .sum();

        assertThat(scheduledTasks).isZero();
    }

    @Test
    @DisplayName("스케줄링이 꺼져도 잡 빈은 그대로 주입되어 수동 호출로 테스트할 수 있다")
    void jobsRemainInjectableForManualInvocation() {
        assertThat(staleSummarySweepJob).isNotNull();
        assertThat(dataRetentionJob).isNotNull();
    }
}
