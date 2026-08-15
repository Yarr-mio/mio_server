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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private static final int GIT_TIMEOUT_SECONDS = 10;
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
     *
     * <p><b>추적되지 않는 파일은 오염으로 세지 않는다</b> (이슈 #371). 이전에는 그냥
     * {@code git status --porcelain} 이라 스크래치 디렉터리 하나만 있어도 모든 실행이
     * "dirty" 로 찍혔다. 그 결과 저장소에 남은 아카이브 4건이 전부 dirty 였고, 표식이
     * <b>"이 수치를 낸 코드가 HEAD 와 다르다"</b> 를 뜻하는지 <b>"누가 임시 폴더를 뒀다"</b>
     * 를 뜻하는지 구별할 수 없었다. 구별할 수 없는 경고는 경고가 아니다.
     *
     * <p>재현 가능성을 좌우하는 것은 추적 중인 파일이 HEAD 와 같은가다. 그것만 본다.
     */
    private static String gitDescribe() {
        String commit = git("rev-parse", "--short", "HEAD");
        if (commit == null) {
            return "unknown";
        }
        String status = git("status", "--porcelain", "--untracked-files=no");
        boolean dirty = status != null && !status.isBlank();
        return commit + (dirty ? " (dirty worktree)" : "");
    }

    /**
     * git 명령을 짧은 상한 안에서 실행한다.
     *
     * <p>출력을 먼저 {@code readAllBytes()} 로 읽으면 프로세스가 stdout 을 닫을 때까지
     * 무한정 막힌다 — index lock 경합처럼 git 이 멈추는 상황에서 타임아웃이 적용될 기회가
     * 없다. 그래서 출력은 별도 스레드에서 읽고, 상한을 넘기면 프로세스를 정리한 뒤 포기한다.
     * 버전 정보를 못 얻는 것은 평가 실행을 막을 이유가 아니므로 실패는 {@code null} 이다.
     */
    private static String git(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);

        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process running = process;
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return null;
                }
            });

            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return null;
            }
            String result = output.get(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return result != null ? result.strip() : null;
        } catch (IOException | ExecutionException | TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
