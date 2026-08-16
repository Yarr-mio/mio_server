package com.mio.ai.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 0단계 — 모델을 <b>부르기 전에</b> 후보를 걸러 놓은 명부 (이슈 #454).
 *
 * <h2>왜 데이터인가</h2>
 *
 * <p>"어떤 모델을 봤고 어떤 모델은 왜 안 봤는가" 는 결과만큼 중요한 기록이다. 그것이 산문으로만
 * 남으면, 나중에 "그 모델은 왜 후보에 없었나" 라는 질문에 아무도 답할 수 없고, 답하지 못하는
 * 비교는 "전부 봤다" 로 반올림된다. 그래서 명부를 커밋된 데이터
 * ({@code src/test/resources/eval/cell/candidate-roster-v1.json})로 두고, 제외 사유를 값으로
 * 남긴다. 사유가 바뀌면 그 변경이 PR 에서 따로 보인다 — 사전 등록 문턱과 같은 취급이다.
 *
 * <h2>단가는 여기 없다</h2>
 *
 * <p>명부는 <b>후보 자격</b>만 담고 단가는 담지 않는다. 로드맵 §11.3 이 "정확한 후보 ID 와
 * 당시 단가는 코드 상수나 문서의 영구 결론으로 고정하지 않고 각 벤치마크 실행의 registry 에
 * 핀한다" 고 정했기 때문이다. 단가는 여전히 {@code -PcellPrices} 로 실행 직전에 들어오고,
 * 핀하지 않은 후보의 원가는 0 이 아니라 미상이다.
 *
 * <p>다만 <b>추론 모델인가</b>는 담는다. 그건 가격이 아니라 모델의 성질이고, 추론 토큰이 출력
 * 단가로 과금된다는 사실이 견적의 의미를 바꾸기 때문이다({@link CellCostEstimator}).
 */
final class CellCandidateRoster {

    private static final String RESOURCE = "/eval/cell/candidate-roster-v1.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 후보 자격 판정.
     *
     * <p>값이 닫혀 있어야 "제외된 모델을 기계적으로 셀 수 있다". 자유 문자열이면 오타 하나가
     * 새 사유가 되고, 사유별 집계가 불가능해진다.
     */
    enum Decision {
        /** 현행 운영 모델. 비교의 분모라 모든 단계에 들어간다. */
        BASELINE(true),
        /** 1단계 스크리닝 대상. */
        SCREEN(true),
        /** 생성 후보에서는 빠지고 offline reference judge 후보로만 남는다. */
        REFERENCE_ONLY(false),
        EXCLUDED_LEGACY_DOMINATED(false),
        EXCLUDED_UNECONOMIC_PER_TURN(false),
        EXCLUDED_UNRESOLVABLE_PRICE(false);

        private final boolean entersScreening;

        Decision(boolean entersScreening) {
            this.entersScreening = entersScreening;
        }

        boolean entersScreening() {
            return entersScreening;
        }

        boolean excluded() {
            return name().startsWith("EXCLUDED_");
        }
    }

    record Entry(String id, Decision decision, boolean reasoningModel, Set<String> roles,
                 String reason) {

        Entry {
            roles = Set.copyOf(roles);
        }

        boolean eligibleFor(CellModelRole role) {
            return roles.contains(role.key());
        }
    }

    private final String version;
    private final String registeredOn;
    private final String source;
    private final List<Entry> entries;

    private CellCandidateRoster(String version, String registeredOn, String source,
                                List<Entry> entries) {
        this.version = version;
        this.registeredOn = registeredOn;
        this.source = source;
        this.entries = List.copyOf(entries);
    }

    static CellCandidateRoster load() {
        try (InputStream in = CellCandidateRoster.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("후보 명부 파일을 찾지 못했다: " + RESOURCE);
            }
            JsonNode root = MAPPER.readTree(in);
            List<Entry> entries = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            root.get("models").forEach(node -> {
                String id = node.get("id").asText();
                if (!seen.add(id)) {
                    throw new IllegalStateException(
                            "후보 명부에 같은 모델이 두 번 있다: " + id + " — 둘 중 어느 판정이 유효한지 알 수 없다");
                }
                Set<String> roles = new LinkedHashSet<>();
                node.withArray("roles").forEach(role -> roles.add(role.asText()));
                String reason = node.path("reason").asText("");
                if (reason.isBlank()) {
                    throw new IllegalStateException(
                            "후보 %s 에 사유가 없다 — 사유 없는 판정은 나중에 검토할 수 없다".formatted(id));
                }
                entries.add(new Entry(id, Decision.valueOf(node.get("decision").asText()),
                        node.path("reasoningModel").asBoolean(false), roles, reason));
            });
            return new CellCandidateRoster(root.get("version").asText(),
                    root.get("registeredOn").asText(), root.get("source").asText(), entries);
        } catch (IOException e) {
            throw new UncheckedIOException("후보 명부를 읽지 못했다", e);
        }
    }

    String version() {
        return version;
    }

    String registeredOn() {
        return registeredOn;
    }

    List<Entry> entries() {
        return entries;
    }

    Optional<Entry> find(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    /** 추론 모델인가. 명부에 없는 ID 는 "모른다" 이므로 거짓이 아니라 보수적으로 참으로 본다. */
    boolean treatAsReasoningModel(String id) {
        return find(id).map(Entry::reasoningModel).orElse(true);
    }

    /** 1단계 스크리닝에 들어가는 생성 후보. 기준선은 셀 A 가 이미 돌리므로 빼고 낸다. */
    List<String> screeningCandidates() {
        return entries.stream()
                .filter(entry -> entry.decision() == Decision.SCREEN)
                .filter(entry -> entry.eligibleFor(CellModelRole.GENERATION))
                .map(Entry::id)
                .toList();
    }

    /** 특정 역할의 후보. 3단계에서 역할별 결선을 짤 때 쓴다. */
    List<String> candidatesFor(CellModelRole role) {
        return entries.stream()
                .filter(entry -> entry.decision().entersScreening()
                        || entry.decision() == Decision.REFERENCE_ONLY)
                .filter(entry -> entry.eligibleFor(role))
                .map(Entry::id)
                .toList();
    }

    /** 제외된 모델을 사유별로. "몇 개를 왜 안 봤는가" 가 한 눈에 보이게 한다. */
    Map<Decision, List<Entry>> excludedByReason() {
        Map<Decision, List<Entry>> grouped = new LinkedHashMap<>();
        entries.stream()
                .filter(entry -> entry.decision().excluded())
                .forEach(entry -> grouped
                        .computeIfAbsent(entry.decision(), key -> new ArrayList<>()).add(entry));
        return grouped;
    }

    /** 0단계 리포트. 모델을 한 번도 부르지 않고 나온다. */
    String render() {
        StringBuilder out = new StringBuilder();
        out.append("\n══════════════════════════════════════════════════════════════\n");
        out.append("  0단계 — 후보 명부 (모델 호출 없음)\n");
        out.append("══════════════════════════════════════════════════════════════\n");
        out.append("  명부 %s (등록 %s) · 출처 %s%n".formatted(version, registeredOn, source));
        out.append("  전체 %d건 · 스크리닝 진입 %d건 · reference 전용 %d건 · 제외 %d건%n".formatted(
                entries.size(),
                entries.stream().filter(e -> e.decision() == Decision.SCREEN).count(),
                entries.stream().filter(e -> e.decision() == Decision.REFERENCE_ONLY).count(),
                entries.stream().filter(e -> e.decision().excluded()).count()));

        out.append("\n  [스크리닝 진입]\n");
        entries.stream()
                .filter(entry -> entry.decision().entersScreening())
                .forEach(entry -> out.append("    %-16s %-10s %s%s%n".formatted(entry.id(),
                        entry.decision(), entry.reasoningModel() ? "[추론] " : "", entry.reason())));

        out.append("\n  [offline reference judge 전용]\n");
        entries.stream()
                .filter(entry -> entry.decision() == Decision.REFERENCE_ONLY)
                .forEach(entry -> out.append("    %-16s %s%n".formatted(entry.id(), entry.reason())));

        out.append("\n  [제외 — 사유별]\n");
        excludedByReason().forEach((decision, group) -> {
            out.append("    %s (%d건)%n".formatted(decision, group.size()));
            group.forEach(entry -> out.append("      %-16s %s%n"
                    .formatted(entry.id(), entry.reason())));
        });

        out.append("\n  ** 단가는 이 명부에 없다 — 실행 직전 -PcellPrices 로 핀하고, 핀하지 않은 "
                + "후보의 원가는 0 이 아니라 미상이다. **\n");
        out.append("══════════════════════════════════════════════════════════════\n");
        return out.toString();
    }

    /** manifest 항목. 어느 명부로 후보를 골랐는지가 실행 기록에 남는다. */
    Map<String, String> asManifestFields() {
        return Map.of("candidate_roster",
                "%s (등록 %s, 전체 %d건 중 스크리닝 %d건)".formatted(version, registeredOn,
                        entries.size(), screeningCandidates().size()));
    }
}
