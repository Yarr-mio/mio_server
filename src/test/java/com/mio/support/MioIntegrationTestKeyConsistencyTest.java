package com.mio.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MioIntegrationTest} 의 키와 {@code application-integration-test.yml} 의 키가
 * 갈라지지 않도록 고정한다.
 *
 * <p>둘이 어긋나도 테스트는 통과한다 — 어노테이션 쪽이 이기기 때문이다. 대신 통합 테스트마다
 * 프로퍼티 조합이 달라져 Spring 컨텍스트 캐시가 갈라지고 빌드만 느려진다. 그래서 증상이
 * 아니라 값 자체를 여기서 붙들어 둔다.
 */
class MioIntegrationTestKeyConsistencyTest {

    private static final String YML = "application-integration-test.yml";

    @Test
    @DisplayName("메타 어노테이션의 암호화 키가 integration-test 프로파일 yml 값과 같다")
    void annotationKeyMatchesProfileYml() throws Exception {
        String annotationValue = MioIntegrationTest.ENCRYPTION_KEY_PROPERTY.split("=", 2)[1];
        String ymlValue = readYmlKey();

        assertThat(annotationValue)
                .as("어긋나면 통합 테스트가 컨텍스트를 두 벌 띄운다 — 둘을 함께 고쳐야 한다")
                .isEqualTo(ymlValue);
    }

    @Test
    @DisplayName("암호화 키는 32바이트로 디코드되는 base64 여야 한다")
    void keyDecodesTo32Bytes() {
        String annotationValue = MioIntegrationTest.ENCRYPTION_KEY_PROPERTY.split("=", 2)[1];

        assertThat(Base64.getDecoder().decode(annotationValue))
                .as("AesGcmMessageEncryptor 가 32바이트를 요구한다 — 아니면 컨텍스트 로드가 실패한다")
                .hasSize(32);
    }

    private String readYmlKey() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(YML)) {
            assertThat(in).as(YML + " 을 클래스패스에서 찾지 못했다").isNotNull();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.lines()
                    .filter(line -> line.startsWith("APP_ENCRYPTION_KEY:"))
                    .map(line -> line.split(":", 2)[1].trim())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            YML + " 에 APP_ENCRYPTION_KEY 가 없다"));
        }
    }
}
