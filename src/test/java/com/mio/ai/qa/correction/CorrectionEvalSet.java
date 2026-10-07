package com.mio.ai.qa.correction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 정정 시험 세트. 파일 경로에서 읽는다 (이슈 #551).
 *
 * <p>평가용 세트는 저장소에 넣지 않고 로컬 파일로 두어, 프롬프트를 고치는 쪽이 내용을 볼 수
 * 없게 한다. 그래서 이 클래스는 클래스패스 리소스가 아니라 <b>경로</b>만 받는다.
 *
 * <pre>{@code
 * {
 *   "version": "correction-eval-v1",
 *   "cases": [
 *     { "id": "C-001", "type": "fact", "strength": "strong", "correction": true,
 *       "turns": [ {"role": "USER", "text": "..."},
 *                  {"role": "ASSISTANT", "text": "..."},
 *                  {"role": "USER", "text": "..."} ] },
 *     { "id": "N-001", "type": "control", "correction": false, "turns": [ ... ] }
 *   ]
 * }
 * }</pre>
 */
public record CorrectionEvalSet(String version, List<CorrectionEvalCase> cases) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public CorrectionEvalSet {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("정정 시험 세트: version 이 비었다");
        }
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("정정 시험 세트: cases 가 비었다");
        }
        Set<String> seen = new HashSet<>();
        for (CorrectionEvalCase c : cases) {
            if (!seen.add(c.id())) {
                throw new IllegalArgumentException("정정 시험 세트: id 가 중복됐다: " + c.id());
            }
        }
        cases = List.copyOf(cases);
    }

    /**
     * 결정론적 층화 표본 (파일럿용). 정정 종류와 대조군의 하위 종류(조언 요청 여부로 나눔)를 번갈아
     * 하나씩 뽑아 각 묶음이 고르게 들어가게 한다. 같은 입력이면 항상 같은 표본이다 — 난수를 쓰지 않는다.
     */
    public CorrectionEvalSet sample(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("표본 크기는 1 이상이어야 한다: " + size);
        }
        if (size >= cases.size()) {
            return this;
        }
        java.util.Map<String, java.util.ArrayDeque<CorrectionEvalCase>> groups = new java.util.TreeMap<>();
        for (CorrectionEvalCase c : cases) {
            String key = c.type() + (c.adviceRequested() ? ":advice" : "");
            groups.computeIfAbsent(key, k -> new java.util.ArrayDeque<>()).add(c);
        }
        List<CorrectionEvalCase> picked = new ArrayList<>();
        while (picked.size() < size) {
            boolean any = false;
            for (java.util.ArrayDeque<CorrectionEvalCase> group : groups.values()) {
                if (!group.isEmpty() && picked.size() < size) {
                    picked.add(group.poll());
                    any = true;
                }
            }
            if (!any) {
                break;
            }
        }
        return new CorrectionEvalSet(version + "-sample" + size, picked);
    }

    public static CorrectionEvalSet fromPath(Path path) {
        try {
            return fromJson(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("정정 시험 세트를 읽지 못했다: " + path, e);
        }
    }

    public static CorrectionEvalSet fromJson(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("정정 시험 세트: JSON 이 아니다", e);
        }
        String version = text(root, "version");
        JsonNode casesNode = root.get("cases");
        if (casesNode == null || !casesNode.isArray()) {
            throw new IllegalArgumentException("정정 시험 세트: cases 배열이 없다");
        }
        List<CorrectionEvalCase> cases = new ArrayList<>();
        for (JsonNode node : casesNode) {
            cases.add(parseCase(node));
        }
        return new CorrectionEvalSet(version, cases);
    }

    private static CorrectionEvalCase parseCase(JsonNode node) {
        JsonNode turnsNode = node.get("turns");
        if (turnsNode == null || !turnsNode.isArray()) {
            throw new IllegalArgumentException("정정 시험 케이스 '%s': turns 배열이 없다"
                    .formatted(text(node, "id")));
        }
        List<CorrectionEvalCase.Turn> turns = new ArrayList<>();
        for (JsonNode turn : turnsNode) {
            turns.add(new CorrectionEvalCase.Turn(text(turn, "role"), text(turn, "text")));
        }
        JsonNode correction = node.get("correction");
        if (correction == null || !correction.isBoolean()) {
            throw new IllegalArgumentException("정정 시험 케이스 '%s': correction 은 true/false 여야 한다"
                    .formatted(text(node, "id")));
        }
        JsonNode strength = node.get("strength");
        JsonNode adviceRequested = node.get("adviceRequested");
        if (adviceRequested != null && !adviceRequested.isNull() && !adviceRequested.isBoolean()) {
            throw new IllegalArgumentException("정정 시험 케이스 '%s': adviceRequested 는 true/false 여야 한다"
                    .formatted(text(node, "id")));
        }
        return new CorrectionEvalCase(
                text(node, "id"),
                text(node, "type"),
                strength == null || strength.isNull() ? null : strength.asText(),
                correction.asBoolean(),
                adviceRequested != null && adviceRequested.asBoolean(),
                turns);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
