package com.mio.ai.qa;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 평가 실행 결과를 버전과 함께 파일로 남긴다 (이슈 #295).
 *
 * <p>지금까지 평가는 콘솔 출력으로만 존재했다. 그래서 과거 튜닝 수치가 어떤 코드·모델·정책
 * 버전에서 나온 값인지 복원할 수 없었고, 재평가 결과가 저장소에 남지 않았다
 * ({@code docs/eval/tuning-history.md}). 이 클래스는 실행마다 다음을 한 파일에 묶는다.
 *
 * <ul>
 *   <li>코드 리비전(작업 트리 오염 여부 포함)</li>
 *   <li>데이터셋 버전과 케이스 수</li>
 *   <li>모델·프롬프트·정책 버전</li>
 *   <li>전체·하위 그룹 지표와 실패 케이스 목록</li>
 *   <li>재현 명령</li>
 * </ul>
 *
 * <p>기본 출력 위치는 {@code build/eval-runs/} 이며 커밋 대상이 아니다. 기준선으로 남길
 * 실행만 {@code -Dmio.eval.archiveDir=docs/eval/runs} 로 지정해 저장소에 보관한다.
 */
final class EvalRunArchive {

    private static final String ARCHIVE_DIR_PROPERTY = "mio.eval.archiveDir";
    private static final Path DEFAULT_DIR = Path.of("build", "eval-runs");
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private EvalRunArchive() {
    }

    /**
     * @param runName  실행 이름. 파일명 접두사로 쓰인다
     * @param metadata 버전·환경 정보. 삽입 순서대로 기록된다
     * @param report   본문 — 각 테스트가 이미 만드는 리포트 문자열을 그대로 넣는다
     * @return 기록된 파일 경로
     */
    static Path write(String runName, Map<String, String> metadata, String report) {
        Instant now = Instant.now();
        Map<String, String> header = new LinkedHashMap<>();
        header.put("run_at", now.toString());
        header.put("code_commit", gitDescribe());
        header.putAll(metadata);

        StringBuilder doc = new StringBuilder();
        doc.append("# 평가 실행 기록 — ").append(runName).append("\n\n");
        doc.append("| 항목 | 값 |\n|---|---|\n");
        header.forEach((k, v) -> doc.append("| `").append(k).append("` | ").append(v).append(" |\n"));
        doc.append("\n## 결과\n\n```\n").append(report.strip()).append("\n```\n");

        Path dir = Path.of(System.getProperty(ARCHIVE_DIR_PROPERTY, DEFAULT_DIR.toString()));
        Path file = dir.resolve("%s-%s.md".formatted(FILE_STAMP.format(now), runName));
        try {
            Files.createDirectories(dir);
            Files.writeString(file, doc.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("평가 아카이브를 쓰지 못했다: " + file, e);
        }
        System.out.printf("%n[eval-archive] %s%n", file.toAbsolutePath());
        return file;
    }

    /**
     * 실행 시점의 코드 리비전. 커밋 해시만으로는 부족하다 — 커밋되지 않은 변경 위에서 낸
     * 수치를 나중에 그 커밋의 결과로 오해할 수 있으므로 오염 여부를 함께 남긴다.
     */
    private static String gitDescribe() {
        String commit = git("rev-parse", "--short", "HEAD");
        if (commit == null) {
            return "unknown";
        }
        String status = git("status", "--porcelain");
        boolean dirty = status != null && !status.isBlank();
        return commit + (dirty ? " (dirty worktree)" : "");
    }

    private static String git(String... args) {
        try {
            String[] command = new String[args.length + 1];
            command[0] = "git";
            System.arraycopy(args, 0, command, 1, args.length);
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
                    ? output.strip()
                    : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
