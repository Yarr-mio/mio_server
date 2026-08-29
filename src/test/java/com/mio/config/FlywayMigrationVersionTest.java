package com.mio.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Flyway가 기동 전에 거부할 마이그레이션 버전 중복을 빠른 단위 테스트로 고정한다. */
class FlywayMigrationVersionTest {

    /**
     * 버전부는 숫자와 구분자 {@code _}(= 점)로 이뤄지고, 설명과의 경계는 {@code __} 두 개다.
     *
     * <p>이전 패턴 {@code ^V([^_]+)__} 은 {@code V58_1__...} 같은 점 버전(58.1)을 아예 매칭하지
     * 못해 중복 검사에서 조용히 빠졌다. 검사에서 빠진 파일은 중복이 생겨도 이 테스트가 잡지
     * 못하고 기동 시점에 터진다 (이슈 #530).
     */
    private static final Pattern VERSION = Pattern.compile("^V(\\d+(?:_\\d+)*)__.*\\.sql$");

    @Test
    @DisplayName("모든 versioned migration은 서로 다른 버전을 가진다")
    void versionedMigrations_doNotShareAVersion() throws IOException {
        Path migrations = Path.of("src/main/resources/db/migration");
        Map<String, List<String>> byVersion;
        try (var paths = Files.list(migrations)) {
            byVersion = paths
                    .map(path -> path.getFileName().toString())
                    .map(VERSION::matcher)
                    .filter(Matcher::matches)
                    .collect(Collectors.groupingBy(
                            matcher -> matcher.group(1),
                            TreeMap::new,
                            Collectors.mapping(matcher -> matcher.group(0), Collectors.toList())
                    ));
        }

        Map<String, List<String>> duplicates = byVersion.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(duplicates)
                .as("같은 Flyway 버전 파일이 둘이면 깨끗한 DB 기동이 실패한다")
                .isEmpty();
    }
}
