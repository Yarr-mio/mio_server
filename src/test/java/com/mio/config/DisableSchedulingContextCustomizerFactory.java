package com.mio.config;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.util.List;

/**
 * 모든 Spring 테스트 컨텍스트에서 프로덕션 스케줄러를 끈다 (이슈 #402).
 *
 * <p>{@code META-INF/spring.factories} 로 등록되어 있어 테스트 클래스가 무엇을 선언하든
 * (프로파일 미지정, {@code local}, {@code test}, {@code integration-test}) 예외 없이 적용된다.
 * 프로파일 조건이나 테스트별 {@code properties} 선언으로는 이 보장을 만들 수 없다 — CI 는
 * {@code SPRING_PROFILES_ACTIVE=local} 로 테스트를 돌리고 {@code MioApplicationTests} 도
 * {@code local} 프로파일을 쓰기 때문이다.
 *
 * <p>이 클래스는 {@code src/test} 에만 존재하므로 프로덕션 아티팩트에는 포함되지 않는다.
 */
public class DisableSchedulingContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return DisableSchedulingContextCustomizer.INSTANCE;
    }

    /**
     * 컨텍스트 캐시 키에 들어가므로 {@code equals}/{@code hashCode} 를 구현한다.
     * 모든 테스트가 동일한 값을 갖게 해 캐시가 쪼개지지 않도록 한다.
     */
    static final class DisableSchedulingContextCustomizer implements ContextCustomizer {

        static final DisableSchedulingContextCustomizer INSTANCE = new DisableSchedulingContextCustomizer();

        private DisableSchedulingContextCustomizer() {
        }

        @Override
        public void customizeContext(
                ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "scheduling.enabled=false");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DisableSchedulingContextCustomizer;
        }

        @Override
        public int hashCode() {
            return DisableSchedulingContextCustomizer.class.hashCode();
        }
    }
}
