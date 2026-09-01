package com.mio.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 실 PostgreSQL·Redis 를 쓰는 통합 테스트의 공통 부트스트랩.
 *
 * <h2>{@code APP_ENCRYPTION_KEY} 를 여기서 다시 선언하는 이유</h2>
 *
 * <p>같은 키가 {@code application-integration-test.yml} 에도 있다. 중복처럼 보이지만
 * <b>지우면 로컬 빌드가 깨진다</b> — 프로파일 yml 로는 이길 수 없는 상위 프로퍼티 소스가
 * 하나 있기 때문이다.
 *
 * <p>{@code build.gradle.kts} 는 개발자의 {@code .env} 를 읽어 테스트 JVM 의 <b>환경 변수</b>로
 * 주입한다. Spring 의 우선순위에서 환경 변수는 프로파일 yml 보다 위이므로, 개발자의 실제
 * {@code APP_ENCRYPTION_KEY} 가 테스트용 키를 덮어쓴다. 그 값이 base64 가 아니거나 32바이트가
 * 아니면 {@code AesGcmMessageEncryptor} 생성자가 터지고, 증상은 암호화 오류가 아니라
 * <b>Spring 컨텍스트 로드 실패</b>로 나타난다 — 원인을 찾기 어려운 형태다.
 *
 * <p>{@code @SpringBootTest(properties = ...)} 는 그 환경 변수보다 우선하는 유일한 선언 지점이라
 * 여기 남는다. CI 에는 {@code .env} 가 없어 yml 만으로도 통과하므로, 이 방어는 <b>로컬 전용</b>으로
 * 보이지만 로컬에서만 깨지는 것이야말로 놓치기 쉬운 종류다.
 *
 * <p>값을 바꿔야 한다면 이 파일과 {@code application-integration-test.yml} 을 <b>함께</b> 고친다.
 * 둘이 어긋나면 컨텍스트 캐시가 갈라져 통합 테스트가 컨텍스트를 재기동하고 빌드가 느려진다.
 *
 * <h2>쓰지 않는 경우</h2>
 *
 * <p>프로퍼티 우선순위 자체를 검증하는 테스트({@code SchedulingCannotBeReEnabledByTestPropertyTest})는
 * 이 어노테이션을 쓰지 않는다. 검증 대상을 공용 어노테이션 뒤로 숨기면 그 테스트가 무엇을
 * 주장하는지 읽을 수 없게 된다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(properties = MioIntegrationTest.ENCRYPTION_KEY_PROPERTY)
@ActiveProfiles("integration-test")
public @interface MioIntegrationTest {

    /** {@code application-integration-test.yml} 의 같은 키와 반드시 동일한 값을 유지한다. */
    String ENCRYPTION_KEY_PROPERTY =
            "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
}
