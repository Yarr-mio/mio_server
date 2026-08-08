package com.mio.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 프로덕션 스케줄러 활성화 지점 (이슈 #402).
 *
 * <p>{@code @EnableScheduling} 은 원래 {@link com.mio.MioApplication} 에 붙어 있었다. 그러면
 * {@code @SpringBootTest} 컨텍스트에서도 {@code @Scheduled} 잡 8개가 실제로 돌아, 테스트가 만든
 * 픽스처를 스케줄러가 중간에 가로챈다. {@code StaleSummarySweepJob} 이 아직 요약이 붙기 전의
 * pending 세션을 {@code failed} 로 봉인해 {@code StaleSummarySweepIntegrationTest} 가 CI 에서
 * 간헐 실패한 것이 그 사례다. {@code DataRetentionJob} 은 유저를 하드 삭제하고 리플렉션 잡들은
 * LLM 호출 경로를 타므로, 테스트 오염을 넘어 데이터 삭제·과금 위험도 있었다.
 *
 * <p>그래서 활성화를 별도 설정 클래스로 떼어내 {@code scheduling.enabled} 스위치를 달았다.
 * <b>기본값은 켜짐</b>({@code matchIfMissing = true})이라 프로덕션·로컬 실행은 프로퍼티를 아무것도
 * 두지 않아도 지금까지와 동일하게 동작한다. 끄는 쪽은 테스트 소스에만 존재한다
 * ({@code src/test/resources/application-test.yml}, {@code application-integration-test.yml},
 * 그리고 프로파일을 가리지 않고 모든 테스트 컨텍스트를 덮는 {@code DisableSchedulingContextCustomizerFactory}).
 *
 * <p>프로파일 조건({@code @Profile("!test & !integration-test")})을 쓰지 않은 이유는,
 * CI 가 {@code SPRING_PROFILES_ACTIVE=local} 로 테스트를 돌리고 {@code MioApplicationTests} 도
 * {@code local} 프로파일을 쓰기 때문이다. 프로파일만으로는 "테스트 실행"과 "로컬 구동"을 구분할 수 없다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "scheduling.enabled", matchIfMissing = true)
public class SchedulingConfig {
}
