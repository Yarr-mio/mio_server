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
 *
 * <h2>{@code @SpringBootTest(properties = ...)} 와의 관계 — 이 값은 테스트에서 덮을 수 없다</h2>
 *
 * <p>양쪽 모두 <b>같은</b> 프로퍼티 소스에 쓴다 —
 * {@code TestPropertySourceUtils.INLINED_PROPERTIES_PROPERTY_SOURCE_NAME}, 즉
 * {@code "Inlined Test Properties"} 라는 하나의 {@code MapPropertySource} 다. 그런데도 충돌하지 않는
 * 이유는 소스를 교체하는 게 아니라 <b>기존 맵에 {@code putAll} 로 덧쓰기</b> 때문이고, 키가 서로
 * 다르기 때문이다({@code scheduling.enabled} vs {@code APP_ENCRYPTION_KEY} 등). 소스 이름이 달라서가
 * <i>아니다</i>.
 *
 * <p>적용 순서는 비대칭이다. {@code @SpringBootTest(properties = ...)} 는
 * {@code SpringBootContextLoader.prepareEnvironment} 단계의
 * {@code PrepareEnvironmentListener}({@code Ordered.HIGHEST_PRECEDENCE})로 <b>먼저</b> 적용되고,
 * {@code ContextCustomizer} 는 {@code prepareContext()} 단계의 {@code ApplicationContextInitializer}
 * 로 <b>나중에</b> 적용된다. 같은 맵에 나중에 쓰는 쪽이 이기므로 <b>이 커스터마이저가 항상 최종값을
 * 결정한다</b>.
 *
 * <p>따라서 {@code @SpringBootTest(properties = "scheduling.enabled=true")} 로는 스케줄링을 다시 켤 수
 * 없다 — 조용히 {@code false} 로 되돌아간다. 스케줄러가 실제로 도는 것을 검증해야 한다면 이 경로 대신
 * {@code SchedulingConfigTest} 처럼 {@code ApplicationContextRunner} 를 쓰거나, 잡의 {@code run()} 을
 * 직접 호출하라.
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
