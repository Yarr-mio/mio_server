package com.mio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
class MioApplicationTests {

    @Autowired private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
    }

    @Test
    @DisplayName("local 프로파일 테스트 컨텍스트에서도 프로덕션 스케줄러는 돌지 않는다")
    void schedulingIsDisabledEvenOnLocalProfile() {
        // 이슈 #402 — CI 는 SPRING_PROFILES_ACTIVE=local 로 테스트를 돌린다. 프로파일 조건만으로
        // 껐다면 이 컨텍스트에서 @Scheduled 잡 8개가 그대로 실행됐을 것이다.
        assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class))
                .isEmpty();
    }
}
