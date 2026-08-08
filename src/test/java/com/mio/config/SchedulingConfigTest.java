package com.mio.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스케줄링 스위치의 양방향 회귀 가드 (이슈 #402).
 *
 * <p>테스트에서 스케줄러를 끈 조치가 <b>프로덕션 스케줄링까지 꺼버리는</b> 사고로 번지지 않도록,
 * "프로퍼티가 없으면 실제로 {@code @Scheduled} 메서드가 돈다"를 실행으로 확인한다. 이게 깨지면
 * 알림·세션 타임아웃·리포트 집계가 전부 멈춘다.
 *
 * <p>Spring 테스트 컨텍스트가 아니라 {@link ApplicationContextRunner} 를 쓰는 이유는,
 * {@link DisableSchedulingContextCustomizerFactory} 가 <i>모든</i> 테스트 컨텍스트의 스케줄링을 끄기
 * 때문이다. 러너는 그 경로를 타지 않으므로 프로덕션과 동일한 기본값을 관찰할 수 있다.
 */
class SchedulingConfigTest {

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class, CountingJob.class);

    @Test
    @DisplayName("프로퍼티가 없으면(프로덕션 기본) 스케줄러가 등록되고 @Scheduled 메서드가 실제로 실행된다")
    void schedulingEnabledByDefault() {
        RUNNER.run(context -> {
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context.getBean(CountingJob.class).awaitFirstRun())
                    .as("기본 설정에서 @Scheduled 잡이 실행되어야 한다")
                    .isTrue();
        });
    }

    @Test
    @DisplayName("scheduling.enabled=false 면 스케줄러가 아예 등록되지 않고 @Scheduled 메서드도 돌지 않는다")
    void schedulingDisabledByProperty() {
        RUNNER.withPropertyValues("scheduling.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context).doesNotHaveBean(SchedulingConfig.class);
            assertThat(context.getBean(CountingJob.class).ranWithin(300))
                    .as("스케줄링이 꺼졌는데 @Scheduled 잡이 실행되면 안 된다")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("프로덕션 설정 파일은 스케줄링을 끄지 않는다")
    void productionConfigNeverDisablesScheduling() {
        assertThat(schedulingEnabledIn("application.yml"))
                .as("application.yml 이 스케줄링을 끄면 프로덕션 배치가 전부 멈춘다")
                .isNotEqualTo("false");
        assertThat(schedulingEnabledIn("application-prod.yml"))
                .as("application-prod.yml 이 스케줄링을 끄면 프로덕션 배치가 전부 멈춘다")
                .isNotEqualTo("false");
    }

    @Test
    @DisplayName("두 테스트 프로파일 모두 스케줄링을 끈다")
    void bothTestProfilesDisableScheduling() {
        assertThat(schedulingEnabledIn("application-test.yml")).isEqualTo("false");
        assertThat(schedulingEnabledIn("application-integration-test.yml")).isEqualTo("false");
    }

    /** 해당 설정 파일이 선언한 {@code scheduling.enabled} 값. 선언이 없으면 {@code null}. */
    private String schedulingEnabledIn(String yamlName) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(yamlName));
        Properties properties = factory.getObject();
        Object value = properties == null ? null : properties.get("scheduling.enabled");
        return value == null ? null : value.toString();
    }

    /** 스케줄러가 실제로 도는지 관찰하기 위한 최소 잡. */
    static class CountingJob {

        private final CountDownLatch firstRun = new CountDownLatch(1);

        @Scheduled(fixedDelay = 20)
        void run() {
            firstRun.countDown();
        }

        boolean awaitFirstRun() throws InterruptedException {
            return firstRun.await(5, TimeUnit.SECONDS);
        }

        boolean ranWithin(long millis) throws InterruptedException {
            return firstRun.await(millis, TimeUnit.MILLISECONDS);
        }
    }
}
