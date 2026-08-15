package com.mio.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class CiWorkflowContractTest {

    private static final Path WORKFLOW_DIR = Path.of(".github", "workflows");

    @Test
    @DisplayName("통합 브랜치 push와 PR은 기본 build CI 대상이다")
    void ciIncludesIntegrationBranches() throws IOException {
        String workflow = Files.readString(WORKFLOW_DIR.resolve("ci.yml"));

        Pattern push = Pattern.compile(
                "(?ms)^\\s*push:\\s*\\R\\s*branches:\\s*\\[[^]]*integration/\\*\\*[^]]*]");
        Pattern pullRequest = Pattern.compile(
                "(?ms)^\\s*pull_request:\\s*\\R\\s*branches:\\s*\\[[^]]*integration/\\*\\*[^]]*]");

        assertThat(workflow).containsPattern(push).containsPattern(pullRequest);
    }

    @Test
    @DisplayName("위기 평가는 기본 브랜치에 등록되지만 고정 integration ref만 checkout한다")
    void crisisEvalDispatcherPinsTrustedIntegrationRef() throws IOException {
        Path workflowPath = WORKFLOW_DIR.resolve("crisis-eval.yml");
        assertThat(workflowPath).exists();

        String workflow = Files.readString(workflowPath);
        assertThat(workflow)
                .contains("workflow_dispatch:")
                .contains("ref: integration/1.1.0")
                .contains("persist-credentials: false")
                .contains("OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}")
                .contains("Validate evaluation secret")
                .doesNotContain("ref: ${{ inputs.");
    }
}
