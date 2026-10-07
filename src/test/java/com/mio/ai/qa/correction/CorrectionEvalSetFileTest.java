package com.mio.ai.qa.correction;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로컬 평가용 세트 파일을 검증하고 <b>집계만</b> 출력한다 (이슈 #554).
 *
 * <pre>{@code
 * MIO_EVAL_CORRECTION_SET_PATH=eval-private/correction-eval-v1.json \
 *   ./gradlew test --tests "com.mio.ai.qa.correction.CorrectionEvalSetFileTest" -i
 * }</pre>
 *
 * <p>환경 변수가 없으면 건너뛴다 — 평가용 세트는 저장소에 없으므로 일반 빌드는 이 테스트를 돌리지 않는다.
 * 케이스 본문·id 는 출력하지 않는다.
 */
@DisplayName("[QA] 로컬 정정 시험 세트 파일 검증")
class CorrectionEvalSetFileTest {

    static final String PATH_ENV = "MIO_EVAL_CORRECTION_SET_PATH";

    @Test
    @DisplayName("파일을 읽어 형식을 검증하고 건수 분포와 sha256 만 출력한다")
    void validatesLocalSetAndPrintsOnlyAggregates() throws Exception {
        String path = System.getenv(PATH_ENV);
        Assumptions.assumeTrue(path != null && !path.isBlank(),
                PATH_ENV + " 가 없다 — 로컬 시험 세트 검증을 건너뛴다");

        Path file = Path.of(path);
        CorrectionEvalSet set = CorrectionEvalSet.fromPath(file);
        String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));

        System.out.print(CorrectionEvalSetSummary.of(set).render(sha256));

        assertThat(set.cases()).anyMatch(CorrectionEvalCase::correction);
        assertThat(set.cases()).anyMatch(c -> !c.correction());
    }
}
