package com.mio.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @SpringBootTest(properties = ...)} 로는 스케줄링을 되켤 수 없다는 사실을 못 박는다 (이슈 #402).
 *
 * <p>{@link DisableSchedulingContextCustomizerFactory} 의 Javadoc 이 설명하는 동작을 주석으로만 두지
 * 않고 테스트로 고정한다. 두 메커니즘은 같은 {@code "Inlined Test Properties"} 맵에 쓰고, 커스터마이저가
 * {@code prepareContext()} 단계로 <b>나중에</b> 적용되어 최종값을 결정한다. 그래서 아래처럼 명시적으로
 * {@code true} 를 줘도 조용히 {@code false} 로 되돌아간다.
 *
 * <p>이 성질이 바뀌면(예: Spring 이 적용 순서를 바꾸면) 테스트 실행 중 프로덕션 스케줄러가 다시 살아날
 * 수 있으므로, 그때 이 테스트가 먼저 알려주게 한다.
 */
@SpringBootTest(properties = {
        "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "scheduling.enabled=true"
})
@ActiveProfiles("integration-test")
class SchedulingCannotBeReEnabledByTestPropertyTest {

    @Autowired private ApplicationContext applicationContext;
    @Autowired private Environment environment;

    @Test
    @DisplayName("테스트가 scheduling.enabled=true 를 줘도 커스터마이저가 나중에 덮어 false 로 남는다")
    void testPropertyCannotReEnableScheduling() {
        assertThat(environment.getProperty("scheduling.enabled")).isEqualTo("false");
        assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class))
                .as("@SpringBootTest properties 로 스케줄러가 되살아나면 테스트 격리가 다시 깨진다")
                .isEmpty();
    }
}
