package com.mio.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryStatusMigrationSafetyTest {

    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    @Test
    @DisplayName("상태 제약은 NOT VALID 추가와 후속 VALIDATE로 쓰기 잠금을 분리한다")
    void checkConstraintsAreAddedAndValidatedInSeparatePhases() throws IOException {
        String addColumns = Files.readString(MIGRATIONS.resolve("V60__split_summary_component_statuses.sql"));
        String validate = Files.readString(MIGRATIONS.resolve("V61__validate_summary_component_constraints.sql"));

        assertThat(addColumns).containsSubsequence(
                "ck_session_summaries_user_render_status", "NOT VALID",
                "ck_session_summaries_todo_status", "NOT VALID");
        assertThat(addColumns).doesNotContain("CREATE INDEX");
        assertThat(validate)
                .contains("VALIDATE CONSTRAINT ck_session_summaries_user_render_status")
                .contains("VALIDATE CONSTRAINT ck_session_summaries_todo_status");
    }

    @Test
    @DisplayName("pending 인덱스는 Flyway 트랜잭션 밖에서 concurrently 생성한다")
    void pendingIndexesAreCreatedConcurrentlyOutsideTransaction() throws IOException {
        String index = Files.readString(MIGRATIONS.resolve("V62__create_summary_component_pending_indexes.sql"));
        String scriptConfig = Files.readString(
                MIGRATIONS.resolve("V62__create_summary_component_pending_indexes.sql.conf"));
        String application = Files.readString(Path.of("src", "main", "resources", "application.yml"));

        assertThat(index)
                .contains("CREATE INDEX CONCURRENTLY")
                .contains("user_render_pending_at")
                .contains("todo_pending_at");
        assertThat(scriptConfig.trim()).isEqualTo("executeInTransaction=false");
        assertThat(application)
                .containsSubsequence("flyway:", "postgresql:", "transactional-lock: false");
    }
}
