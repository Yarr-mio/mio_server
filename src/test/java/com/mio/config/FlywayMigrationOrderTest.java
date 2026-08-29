package com.mio.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 버전 중복과 순서를 기동 전에 잡는다 (이슈 #530).
 *
 * <p>이 핫픽스는 점 버전 {@code V58_1}(= 58.1)을 쓴다. 프로덕션은 V58, develop 은 V76 이라
 * V59 는 중복이고 V77 은 승격 시 V59~V76 이 out-of-order 가 되어 기동이 실패한다.
 * 58 &lt; 58.1 &lt; 59 라는 전제가 깨지면 프로덕션 배포가 막히므로 테스트로 고정한다.
 */
class FlywayMigrationOrderTest {

    /** 버전부는 숫자와 {@code _}(= 점) 조합이고, 설명과의 경계는 {@code __} 두 개다. */
    private static final Pattern VERSION = Pattern.compile("^V(\\d+(?:_\\d+)*)__.*\\.sql$");

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Test
    @DisplayName("모든 versioned migration은 서로 다른 버전을 가진다")
    void versionedMigrations_doNotShareAVersion() throws IOException {
        Map<String, List<String>> duplicates = versions().entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(duplicates)
                .as("같은 Flyway 버전 파일이 둘이면 깨끗한 DB 기동이 실패한다")
                .isEmpty();
    }

    @Test
    @DisplayName("선제 인사 마이그레이션은 58과 59 사이에 놓인다")
    void sessionOpeningMigration_sortsBetween58And59() throws IOException {
        assertThat(versions()).containsKey("58_1");

        Comparator<String> byVersion = Comparator.comparing(FlywayMigrationOrderTest::toComparable);

        assertThat(byVersion.compare("58", "58_1"))
                .as("58 < 58.1 이어야 프로덕션(V58 적용 완료)이 이 마이그레이션을 실행한다")
                .isNegative();
        assertThat(byVersion.compare("58_1", "59"))
                .as("58.1 < 59 여야 develop 승격 때 V59~V76 이 out-of-order 가 되지 않는다")
                .isNegative();
    }

    /** 파일 이름의 버전부 → 파일 목록. */
    private Map<String, List<String>> versions() throws IOException {
        try (Stream<Path> paths = Files.list(MIGRATIONS)) {
            return paths
                    .map(path -> path.getFileName().toString())
                    .map(VERSION::matcher)
                    .filter(Matcher::matches)
                    .collect(Collectors.groupingBy(
                            matcher -> matcher.group(1),
                            Collectors.mapping(matcher -> matcher.group(0), Collectors.toList())
                    ));
        }
    }

    /** Flyway 와 같은 규칙으로 버전을 비교 가능한 문자열로 정규화한다 (각 파트를 0-패딩). */
    private static String toComparable(String version) {
        return Stream.of(version.split("_"))
                .map(part -> String.format("%010d", Integer.parseInt(part)))
                .collect(Collectors.joining("."));
    }
}
