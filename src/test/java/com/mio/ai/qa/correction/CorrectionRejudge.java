package com.mio.ai.qa.correction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.qa.correction.CorrectionEvalRunner.ScoredSample;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

/**
 * 저장된 실행 결과의 <b>응답을 다시 생성하지 않고 채점만 다시 한다</b> (이슈 #555, #552).
 *
 * <p>기준표를 고칠 때 "고쳐졌는가"를 확인하는 도구다. 응답 생성이 비용의 대부분이므로(파일럿 기준 약 87%),
 * 같은 응답을 새 기준표로 다시 채점하면 몇 원으로 기준표 변경의 효과를 볼 수 있다. 같은 응답에 대한
 * 이전 판정과 새 판정을 항목별로 비교한다.
 *
 * <p><b>튜닝용 세트의 결과에만 쓴다.</b> 평가용 세트의 응답을 보고 기준표를 고치면 평가 세트가 기준표에
 * 노출된다 — 세트 버전이 {@value #DEV_SET_PREFIX} 로 시작하지 않으면 거부한다.
 */
final class CorrectionRejudge {

    static final String DEV_SET_PREFIX = "correction-dev";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CorrectionRejudge() {}

    /** 아카이브에서 다시 읽은 표본과 그때의 판정. */
    record Archived(String mode, CorrectionGenerationRunner.Sample sample, Map<String, Boolean> oldItems,
                    boolean oldPassed, boolean oldJudgeFailed, boolean hasVerdict) {}

    record Loaded(CorrectionRunIdentity identity, List<Archived> samples) {}

    static Loaded load(Path archiveFile) {
        try {
            JsonNode root = MAPPER.readTree(Files.readString(archiveFile, StandardCharsets.UTF_8));
            JsonNode id = root.get("identity");
            CorrectionRunIdentity identity = new CorrectionRunIdentity(id.get("runId").asText(),
                    java.time.Instant.parse(id.get("startedAt").asText()), id.get("setVersion").asText(),
                    id.get("setSha256").asText(), id.get("repeats").asInt(), strings(id.get("modes")),
                    strings(id.get("arms")), id.get("generationModel").asText(), id.get("judgeModel").asText(),
                    id.get("rubricVersion").asText());
            List<Archived> samples = new ArrayList<>();
            for (JsonNode s : root.get("samples")) {
                CorrectionGenerationRunner.Sample sample = new CorrectionGenerationRunner.Sample(
                        s.get("caseId").asText(), CorrectionPromptArm.valueOf(s.get("arm").asText()),
                        s.get("repeat").asInt(), s.get("response").asText(), s.get("failed").asBoolean(),
                        s.get("truncated").asBoolean());
                JsonNode verdict = s.get("verdict");
                Map<String, Boolean> items = new LinkedHashMap<>();
                boolean passed = false;
                boolean judgeFailed = false;
                if (verdict != null) {
                    verdict.get("items").fields().forEachRemaining(e -> items.put(e.getKey(), e.getValue().asBoolean()));
                    passed = verdict.get("passed").asBoolean();
                    judgeFailed = verdict.get("judgeFailed").asBoolean();
                }
                samples.add(new Archived(s.get("mode").asText(), sample, items, passed, judgeFailed, verdict != null));
            }
            return new Loaded(identity, samples);
        } catch (IOException e) {
            throw new UncheckedIOException("실행 결과 파일을 읽지 못했다: " + archiveFile, e);
        }
    }

    /**
     * @throws IllegalStateException 튜닝용 세트가 아니거나, 세트 파일이 실행 당시와 다르거나, 케이스가 없다
     */
    static void requireRejudgeable(CorrectionRunIdentity identity, CorrectionEvalSet set, String currentSetSha256) {
        if (!identity.setVersion().startsWith(DEV_SET_PREFIX)) {
            throw new IllegalStateException("세트 '%s' 는 튜닝용이 아니다 — 평가용 세트의 응답으로 기준표를 조정하지 않는다"
                    .formatted(identity.setVersion()));
        }
        if (!identity.setSha256().equals(currentSetSha256)) {
            throw new IllegalStateException("세트 파일이 실행 당시와 다르다 (해시 불일치) — 같은 세트로 다시 채점해야 비교할 수 있다");
        }
    }

    /** 응답을 새 기준표로 다시 채점한다. 결과 순서는 입력 순서와 같다. 예산을 넘으면 남은 표본은 채점하지 않는다. */
    static List<ScoredSample> rejudge(Loaded loaded, CorrectionEvalSet set, CorrectionJudge judge,
                                      BooleanSupplier budgetExceeded) {
        Map<String, CorrectionEvalCase> cases = new LinkedHashMap<>();
        set.cases().forEach(c -> cases.put(c.id(), c));
        List<Archived> in = loaded.samples();
        ScoredSample[] out = new ScoredSample[in.size()];
        IntStream.range(0, in.size()).parallel().forEach(i -> {
            Archived a = in.get(i);
            CorrectionEvalCase evalCase = cases.get(a.sample().caseId());
            if (evalCase == null) {
                throw new IllegalStateException("세트에 없는 케이스: " + a.sample().caseId());
            }
            if (a.sample().failed() || budgetExceeded.getAsBoolean()) {
                out[i] = new ScoredSample(a.mode(), a.sample(), null, null);
                return;
            }
            out[i] = new ScoredSample(a.mode(), a.sample(), judge.judge(evalCase, a.sample().response()),
                    CorrectionResponseShape.of(a.sample().response()));
        });
        return List.of(out);
    }

    /**
     * 저장된 판정 항목으로 통과 여부만 현재 규칙으로 다시 계산한다 — LLM 을 부르지 않는다. 통과 조건(코드)이
     * 바뀌었을 때(기준표 v4: 대조군에서 '어색한 재진술'을 통과 조건에서 뺌) 같은 판정 항목으로 결과를 다시 볼 때 쓴다.
     */
    static List<ScoredSample> reaggregate(Loaded loaded, CorrectionEvalSet set) {
        Map<String, CorrectionEvalCase> cases = new LinkedHashMap<>();
        set.cases().forEach(c -> cases.put(c.id(), c));
        List<ScoredSample> out = new ArrayList<>();
        for (Archived a : loaded.samples()) {
            CorrectionEvalCase evalCase = cases.get(a.sample().caseId());
            if (evalCase == null) {
                throw new IllegalStateException("세트에 없는 케이스: " + a.sample().caseId());
            }
            if (a.sample().failed()) {
                out.add(new ScoredSample(a.mode(), a.sample(), null, null));
                continue;
            }
            CorrectionVerdict verdict;
            if (!a.hasVerdict() || a.oldJudgeFailed()) {
                verdict = CorrectionVerdict.failed(evalCase);
            } else {
                verdict = evalCase.correction()
                        ? CorrectionVerdict.forCorrection(evalCase, a.oldItems(), Map.of())
                        : CorrectionVerdict.forControl(evalCase, a.oldItems(), Map.of());
            }
            out.add(new ScoredSample(a.mode(), a.sample(), verdict,
                    CorrectionResponseShape.of(a.sample().response())));
        }
        return List.copyOf(out);
    }

    /** 이전 판정과 새 판정의 차이를 사람이 읽을 수 있는 줄로 만든다. 튜닝용 세트에만 쓴다(케이스 id 포함). */
    static String describeChanges(Loaded loaded, List<ScoredSample> rescored) {
        StringBuilder sb = new StringBuilder();
        int itemFlips = 0;
        int passFlips = 0;
        int compared = 0;
        Map<String, Integer> flipsByItem = new LinkedHashMap<>();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < rescored.size(); i++) {
            Archived old = loaded.samples().get(i);
            ScoredSample now = rescored.get(i);
            if (!old.hasVerdict() || old.oldJudgeFailed() || now.verdict() == null || now.verdict().judgeFailed()) {
                continue;
            }
            compared++;
            List<String> changed = new ArrayList<>();
            for (Map.Entry<String, Boolean> e : now.verdict().items().entrySet()) {
                Boolean before = old.oldItems().get(e.getKey());
                if (before != null && !before.equals(e.getValue())) {
                    changed.add("%s %s→%s".formatted(e.getKey(), label(before), label(e.getValue())));
                    flipsByItem.merge(e.getKey(), 1, Integer::sum);
                    itemFlips++;
                }
            }
            boolean passChanged = old.oldPassed() != now.verdict().passed();
            if (passChanged) {
                passFlips++;
            }
            if (!changed.isEmpty() || passChanged) {
                lines.append("  %s · %s · %s: 통과 %s→%s (%s)%n".formatted(old.sample().caseId(), old.mode(),
                        old.sample().arm(), label(old.oldPassed()), label(now.verdict().passed()),
                        String.join(", ", changed)));
            }
        }
        sb.append("%n[correction-rejudge] 비교한 표본 %d개 · 항목 판정이 바뀐 것 %d건 · 통과 여부가 바뀐 표본 %d개%n"
                .formatted(compared, itemFlips, passFlips));
        sb.append("  항목별 변경: ").append(flipsByItem.isEmpty() ? "없음" : flipsByItem).append('\n');
        sb.append(lines);
        return sb.toString();
    }

    private static String label(boolean value) {
        return value ? "예" : "아니오";
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }
}
