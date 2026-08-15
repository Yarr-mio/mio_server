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

    private static final Pattern VERSION = Pattern.compile("^V([^_]+)__.*\\.sql$");

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
