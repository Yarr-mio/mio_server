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
    @DisplayName("CI 는 main·develop 만 본다 — 폐기된 integration 트리거가 되살아나면 실패한다")
    void ciTargetsOnlyMainAndDevelop() throws IOException {
        String workflow = Files.readString(WORKFLOW_DIR.resolve("ci.yml"));

        // 브랜치 전략 개정으로 integration/* 이 폐기됐다 (이슈 #488).
        // 트리거가 남아 있으면 존재하지 않는 브랜치를 검증하는 죽은 설정이 된다.
        Pattern push = Pattern.compile(
                "(?ms)^\\s*push:\\s*\\R\\s*branches:\\s*\\[[^]]*develop[^]]*]");
        Pattern pullRequest = Pattern.compile(
                "(?ms)^\\s*pull_request:\\s*\\R\\s*branches:\\s*\\[[^]]*develop[^]]*]");

        assertThat(workflow)
                .containsPattern(push)
                .containsPattern(pullRequest)
                .contains("main")
                .doesNotContain("integration");
    }

    @Test
    @DisplayName("위기 평가는 기본 브랜치에 등록되지만 신뢰 브랜치(develop) ref만 checkout한다")
    void crisisEvalDispatcherPinsTrustedRef() throws IOException {
        Path workflowPath = WORKFLOW_DIR.resolve("crisis-eval.yml");
        assertThat(workflowPath).exists();

        // 브랜치 전략 개정으로 integration/* 이 폐기되면서 신뢰 브랜치가 develop 이 됐다.
        // 계약의 핵심은 그대로다: 임의 브랜치 코드가 시크릿으로 실행되면 안 된다 —
        // ref 가 입력값이 되거나 폐기된 브랜치를 가리키면 실패한다.
        String workflow = Files.readString(workflowPath);
        assertThat(workflow)
                .contains("workflow_dispatch:")
                .contains("ref: develop")
                .contains("persist-credentials: false")
                .contains("OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}")
                .contains("Validate evaluation secret")
                .doesNotContain("ref: ${{ inputs.")
                .doesNotContain("ref: integration");
    }
}
