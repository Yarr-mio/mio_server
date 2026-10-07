package com.mio.ai.qa.correction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 채점기 사람 검증 도구 (이슈 #555). 사람이 <b>채점기 결과를 보지 않고</b> 응답을 직접 채점하고, 그 결과를
 * 채점기와 항목별로 비교한다.
 *
 * <ul>
 *   <li>{@link #export}: 저장된 실행 결과에서 응답을 골라 채점표(CSV)와 정답 열쇠(JSON), 채점 안내(MD)를 만든다.
 *       채점표에는 채점기 판정·팔·모드가 없다. 열쇠에만 있다.</li>
 *   <li>{@link #compare}: 사람이 채운 채점표와 열쇠를 비교해 항목별 일치율과 불일치 목록을 만든다.</li>
 * </ul>
 *
 * <p><b>표본 추출은 채점기의 판정으로 층화한다</b> — 채점기가 "예"라고 한 것과 "아니오"라고 한 것을 모두 넣어야
 * 과잉 판정(오탐)과 놓침(미탐)을 둘 다 볼 수 있다. 순서는 해시로 섞어 채점표에서는 층이 드러나지 않는다.
 * 튜닝용 세트의 결과에만 쓴다 (채점기 조정 근거가 되므로).
 */
final class CorrectionCalibration {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** 채점표 항목 열 (정정 6개 + 대조군 3개). 사람에게 보이는 이름 → 채점기 항목 키. */
    static final Map<String, String> ITEM_COLUMNS = new LinkedHashMap<>();

    static {
        ITEM_COLUMNS.put("인정", CorrectionVerdict.ACKNOWLEDGED);
        ITEM_COLUMNS.put("프레임재사용", CorrectionVerdict.REUSED_FRAME);
        ITEM_COLUMNS.put("같은조언반복", CorrectionVerdict.REPEATED_ADVICE);
        ITEM_COLUMNS.put("새조언", CorrectionVerdict.NEW_GENERIC_ADVICE);
        ITEM_COLUMNS.put("재진술", CorrectionVerdict.RESTATED);
        ITEM_COLUMNS.put("지어낸내용", CorrectionVerdict.INVENTED_FACTS);
        ITEM_COLUMNS.put("불필요한인정", CorrectionVerdict.SPURIOUS_ACKNOWLEDGMENT);
        ITEM_COLUMNS.put("어색한재진술", CorrectionVerdict.UNNATURAL_RESTATEMENT);
        ITEM_COLUMNS.put("요청된조언회피", CorrectionVerdict.AVOIDED_REQUESTED_ADVICE);
    }

    private CorrectionCalibration() {}

    record Exported(Path sheet, Path key, Path guide, int size) {}

    // ── 내보내기 ──────────────────────────────────────────────────

    static Exported export(Path archiveFile, CorrectionEvalSet set, int size, Path outDir) {
        CorrectionRejudge.Loaded loaded = CorrectionRejudge.load(archiveFile);
        if (!loaded.identity().setVersion().startsWith(CorrectionRejudge.DEV_SET_PREFIX)) {
            throw new IllegalStateException("튜닝용 세트가 아니다 — 채점기 조정 근거로 쓸 수 없다: "
                    + loaded.identity().setVersion());
        }
        Map<String, CorrectionEvalCase> cases = new LinkedHashMap<>();
        set.cases().forEach(c -> cases.put(c.id(), c));

        List<CorrectionRejudge.Archived> pool = loaded.samples().stream()
                .filter(a -> !a.sample().failed() && a.hasVerdict() && !a.oldJudgeFailed())
                .collect(Collectors.toList());
        List<CorrectionRejudge.Archived> picked = select(pool, cases, size, loaded.identity().runId());

        try {
            Files.createDirectories(outDir);
            String runId = loaded.identity().runId();
            Path sheet = outDir.resolve(runId + "-sheet.csv");
            Path key = outDir.resolve(runId + "-key.json");
            Path guide = outDir.resolve(runId + "-guide.md");

            StringBuilder csv = new StringBuilder();
            csv.append('﻿'); // 엑셀에서 한글이 깨지지 않게 BOM 을 붙인다
            csv.append(csvRow(List.of("sheet_id", "구분", "type", "조언요청", "대화", "응답",
                    "인정", "프레임재사용", "같은조언반복", "새조언", "재진술", "지어낸내용",
                    "불필요한인정", "어색한재진술", "요청된조언회피")));
            ObjectNode keyRoot = MAPPER.createObjectNode();
            keyRoot.put("runId", runId);
            keyRoot.put("rubricVersion", loaded.identity().rubricVersion());
            ObjectNode keySamples = keyRoot.putObject("samples");

            for (int i = 0; i < picked.size(); i++) {
                CorrectionRejudge.Archived a = picked.get(i);
                CorrectionEvalCase evalCase = cases.get(a.sample().caseId());
                String id = "S-%02d".formatted(i + 1);
                List<String> row = new ArrayList<>(List.of(id, evalCase.correction() ? "정정" : "대조군",
                        evalCase.type(), evalCase.adviceRequested() ? "Y" : "", conversation(evalCase),
                        a.sample().response()));
                for (int c = 0; c < ITEM_COLUMNS.size(); c++) {
                    row.add("");
                }
                csv.append(csvRow(row));

                ObjectNode k = keySamples.putObject(id);
                k.put("caseId", evalCase.id());
                k.put("correction", evalCase.correction());
                k.put("adviceRequested", evalCase.adviceRequested());
                k.put("mode", a.mode());
                k.put("arm", a.sample().arm().name());
                k.put("repeat", a.sample().repeat());
                k.set("judgeItems", MAPPER.valueToTree(a.oldItems()));
            }
            Files.writeString(sheet, csv.toString(), StandardCharsets.UTF_8);
            Files.writeString(key, MAPPER.writeValueAsString(keyRoot), StandardCharsets.UTF_8);
            Files.writeString(guide, guideText(), StandardCharsets.UTF_8);
            return new Exported(sheet, key, guide, picked.size());
        } catch (IOException e) {
            throw new UncheckedIOException("채점표를 쓰지 못했다: " + outDir, e);
        }
    }

    /**
     * 채점기 판정으로 네 층을 만든다: 대조군·채점기가 '어색한 재진술' 예 / 대조군·아니오 / 정정·'새 조언' 예 / 정정·아니오.
     * <b>전체에서 같은 대화(케이스)가 두 번 나오지 않게</b> 먼저 뽑고, 대화 수가 모자랄 때만 같은 대화의 다른 응답으로
     * 채운다. 뽑힌 순서는 해시로 섞는다. (첫 채점표는 이 규칙이 층 안에서만 적용돼 30건 중 4개 대화가 겹쳤다.)
     */
    static List<CorrectionRejudge.Archived> select(List<CorrectionRejudge.Archived> pool,
                                                   Map<String, CorrectionEvalCase> cases, int size, String salt) {
        List<List<CorrectionRejudge.Archived>> strata = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            strata.add(new ArrayList<>());
        }
        for (CorrectionRejudge.Archived a : pool) {
            CorrectionEvalCase c = cases.get(a.sample().caseId());
            boolean control = !c.correction();
            boolean flagged = Boolean.TRUE.equals(a.oldItems().get(control
                    ? CorrectionVerdict.UNNATURAL_RESTATEMENT : CorrectionVerdict.NEW_GENERIC_ADVICE));
            strata.get((control ? 0 : 2) + (flagged ? 0 : 1)).add(a);
        }
        double[] share = {0.35, 0.15, 0.30, 0.20};
        List<CorrectionRejudge.Archived> picked = new ArrayList<>();
        java.util.Set<String> usedKeys = new java.util.HashSet<>();
        java.util.Set<String> caseSeen = new java.util.HashSet<>();
        for (int s = 0; s < 4; s++) {
            int quota = (int) Math.round(size * share[s]);
            List<CorrectionRejudge.Archived> ordered = strata.get(s).stream()
                    .sorted(Comparator.comparing(a -> hash(salt + "|" + sampleKey(a)))).toList();
            for (CorrectionRejudge.Archived a : ordered) {
                if (picked.size() >= size || countIn(picked, strata.get(s)) >= quota) {
                    break;
                }
                if (caseSeen.add(a.sample().caseId()) && usedKeys.add(sampleKey(a))) {
                    picked.add(a);
                }
            }
        }
        // 모자라면 남은 표본에서 케이스가 겹치지 않는 것부터 채운다
        List<CorrectionRejudge.Archived> rest = pool.stream().filter(a -> !usedKeys.contains(sampleKey(a)))
                .sorted(Comparator.comparing(a -> hash(salt + "|fill|" + sampleKey(a)))).toList();
        // 먼저 아직 안 나온 대화에서 채우고, 그래도 모자랄 때만 같은 대화의 다른 응답을 쓴다
        for (CorrectionRejudge.Archived a : rest) {
            if (picked.size() >= size) {
                break;
            }
            if (caseSeen.add(a.sample().caseId())) {
                usedKeys.add(sampleKey(a));
                picked.add(a);
            }
        }
        for (CorrectionRejudge.Archived a : rest) {
            if (picked.size() >= size) {
                break;
            }
            if (usedKeys.add(sampleKey(a))) {
                picked.add(a);
            }
        }
        return picked.stream().sorted(Comparator.comparing(a -> hash(salt + "|order|" + sampleKey(a)))).toList();
    }

    private static int countIn(List<CorrectionRejudge.Archived> picked, List<CorrectionRejudge.Archived> stratum) {
        java.util.Set<String> keys = stratum.stream().map(CorrectionCalibration::sampleKey)
                .collect(Collectors.toSet());
        return (int) picked.stream().filter(a -> keys.contains(sampleKey(a))).count();
    }

    private static String sampleKey(CorrectionRejudge.Archived a) {
        return a.mode() + "|" + a.sample().arm() + "|" + a.sample().caseId() + "|" + a.sample().repeat();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String conversation(CorrectionEvalCase evalCase) {
        return evalCase.turns().stream()
                .map(t -> (t.isUser() ? "사용자: " : "AI: ") + t.text())
                .collect(Collectors.joining("\n"));
    }

    // ── 비교 ──────────────────────────────────────────────────────

    /** 사람이 채운 채점표와 열쇠를 비교한다. 빈 칸은 그 항목을 채점하지 않은 것으로 보고 건너뛴다. */
    static String compare(Path filledSheet, Path keyFile) {
        try {
            JsonNode key = MAPPER.readTree(Files.readString(keyFile, StandardCharsets.UTF_8));
            List<List<String>> rows = parseCsv(Files.readString(filledSheet, StandardCharsets.UTF_8));
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("채점표가 비었다");
            }
            List<String> header = rows.get(0);
            Map<String, int[]> tally = new LinkedHashMap<>(); // 항목 → {비교, 일치, 사람예채점기아니오, 사람아니오채점기예}
            List<String> disagreements = new ArrayList<>();
            int rowsUsed = 0;
            for (List<String> row : rows.subList(1, rows.size())) {
                if (row.isEmpty() || row.get(0).isBlank()) {
                    continue;
                }
                JsonNode sample = key.get("samples").get(row.get(0).trim());
                if (sample == null) {
                    throw new IllegalArgumentException("열쇠에 없는 sheet_id: " + row.get(0));
                }
                boolean any = false;
                for (Map.Entry<String, String> column : ITEM_COLUMNS.entrySet()) {
                    int idx = header.indexOf(column.getKey());
                    if (idx < 0 || idx >= row.size() || row.get(idx).isBlank()) {
                        continue;
                    }
                    JsonNode judgeValue = sample.get("judgeItems").get(column.getValue());
                    if (judgeValue == null) {
                        // 정정 행에 대조군 칸을(또는 그 반대로) 채웠다 — 행 번호가 어긋났을 수 있으므로 조용히 넘기지 않는다
                        throw new IllegalArgumentException("이 행에는 해당하지 않는 칸을 채웠다: %s · %s"
                                .formatted(row.get(0).trim(), column.getKey()));
                    }
                    Boolean human = parseYesNo(row.get(idx));
                    if (human == null) {
                        throw new IllegalArgumentException("Y/N 으로 읽을 수 없는 값: %s (%s, %s)"
                                .formatted(row.get(idx), row.get(0), column.getKey()));
                    }
                    any = true;
                    boolean judge = judgeValue.asBoolean();
                    int[] t = tally.computeIfAbsent(column.getKey(), k -> new int[4]);
                    t[0]++;
                    if (human == judge) {
                        t[1]++;
                    } else if (human) {
                        t[2]++;
                    } else {
                        t[3]++;
                    }
                    if (human != judge) {
                        disagreements.add("%s · %s: 사람 %s / 채점기 %s".formatted(row.get(0).trim(), column.getKey(),
                                human ? "예" : "아니오", judge ? "예" : "아니오"));
                    }
                }
                if (any) {
                    rowsUsed++;
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("%n[correction-calibration] 채점된 표본 %d개 · 기준표 %s%n".formatted(rowsUsed,
                    key.get("rubricVersion").asText()));
            sb.append("  항목별 일치율 (시작 기준 80% 이상은 근거 없는 제안):\n");
            for (Map.Entry<String, int[]> e : tally.entrySet()) {
                int[] t = e.getValue();
                sb.append("    %s: %d/%d (%.0f%%) · 사람 예/채점기 아니오 %d건(채점기 놓침) · 사람 아니오/채점기 예 %d건(채점기 과잉)%n"
                        .formatted(e.getKey(), t[1], t[0], 100.0 * t[1] / t[0], t[2], t[3]));
            }
            sb.append("  불일치 %d건:\n".formatted(disagreements.size()));
            disagreements.forEach(d -> sb.append("    ").append(d).append('\n'));
            return sb.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("비교하지 못했다", e);
        }
    }

    static Boolean parseYesNo(String value) {
        String v = value.trim().toLowerCase();
        if (List.of("y", "yes", "예", "o", "1", "true", "t").contains(v)) {
            return true;
        }
        if (List.of("n", "no", "아니오", "아니요", "x", "0", "false", "f").contains(v)) {
            return false;
        }
        return null;
    }

    // ── CSV ───────────────────────────────────────────────────────

    static String csvRow(List<String> cells) {
        return cells.stream().map(CorrectionCalibration::csvCell).collect(Collectors.joining(",")) + "\r\n";
    }

    private static String csvCell(String value) {
        String v = value == null ? "" : value;
        return v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")
                ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    /** 따옴표·줄바꿈을 지원하는 최소한의 CSV 파서 (BOM 허용). */
    static List<List<String>> parseCsv(String text) {
        String s = text.startsWith("﻿") ? text.substring(1) : text;
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    // ── 안내문 ────────────────────────────────────────────────────

    static String guideText() {
        return """
                # 채점기 사람 검증 — 채점 안내

                각 행은 대화 하나와 AI의 응답 하나입니다. **응답**을 읽고 아래 항목을 `Y`(예) 또는 `N`(아니오)로 채웁니다.
                채점기가 어떻게 판정했는지는 보지 않은 상태에서 채점하세요. 해당하지 않는 칸은 비워 두세요.

                - `구분`이 **정정**인 행: 인정 ~ 지어낸내용 6칸만 채웁니다.
                - `구분`이 **대조군**인 행: 불필요한인정, 어색한재진술, 요청된조언회피 3칸만 채웁니다
                  (요청된조언회피는 `조언요청`이 Y인 행만).

                ## 정정 행 (사용자가 AI의 직전 말을 바로잡은 상황)
                | 항목 | Y 로 채우는 경우 |
                |---|---|
                | 인정 | 응답이 사용자가 바로잡았다는 사실을 받아들이는 표현이 있음. "제가 잘못 짚었네요", "~가 아니었군요". 일반 공감이나 "그렇군요" 맞장구만 있으면 N |
                | 프레임재사용 | 사용자가 부정한 AI의 이전 해석을 응답이 다시 전제하거나 반복함 (type이 advice_refusal이면 비움) |
                | 같은조언반복 | 직전 AI 발화에 있던 것과 같은 종류의 조언을 다시 함 (직전에 조언이 없었으면 N) |
                | 새조언 | 응답에 사용자에게 무엇을 해 보라고 권하거나 제안하는 표현이 있음. 일반 팁이든 상황에 맞춘 제안이든, "~해보는 것도 좋아요/~해볼까요/~해 보세요"처럼 완곡해도 Y. 감정을 짚는 말은 N |
                | 재진술 | 사용자가 바로잡은 핵심 내용이 응답 안에 그대로 또는 바꿔 말한 형태로 들어 있음. "그렇군요" 한 마디 후 질문으로 넘어가면 N |
                | 지어낸내용 | 사용자가 말하지 않은 구체적인 사정·원인·사건을 응답이 새로 단정함. 폭넓은 공감("힘드셨겠어요")은 N |

                ## 대조군 행 (정정이 아닌 일반 대화)
                | 항목 | Y 로 채우는 경우 |
                |---|---|
                | 불필요한인정 | 사용자가 바로잡지 않았는데 "제가 잘못 짚었네요" 같은 사과·정정 수용 표현이 있음 |
                | 어색한재진술 | 사용자 말을 "그러니까 ~라는 말씀이시군요"처럼 **요약해 되풀이**해서 대화가 부자연스러움. "~하셨군요/~하시겠어요" 같은 평범한 공감은 N. 애매하면 N |
                | 요청된조언회피 | (조언요청=Y일 때만) 사용자가 방법을 물었는데 응답에 권하는 방법이 **하나도 없이** 공감이나 질문으로만 답함. 권하는 표현이 하나라도 있으면 N |

                채점이 끝나면 저장해서(CSV 그대로) 알려 주세요. 채점기와의 일치율을 계산해 드립니다.
                """;
    }
}
