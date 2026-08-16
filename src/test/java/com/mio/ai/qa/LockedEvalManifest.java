package com.mio.ai.qa;

import com.mio.ai.qa.LockedEvalSet.LockedCase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 잠금 매니페스트 대조 로직 (이슈 #454).
 *
 * <h2>왜 테스트에서 분리했는가</h2>
 *
 * <p>{@link LockedEvalSetIntegrityTest} 는 "매니페스트와 다른 케이스가 없다" 를 단언한다.
 * 그 단언은 <b>대조 로직이 아무것도 비교하지 않는 상태</b>와 구분되지 않는다. 그래서 대조를
 * 여기로 빼고, {@link LockedEvalContaminationSelfTest} 가 메모리 위에서 케이스 하나를
 * 변조해 {@link #diff(List)} 가 실제로 그 케이스를 지목하는지 확인한다. 커밋된 파일은
 * 건드리지 않는다 — 잠금을 검증하려고 잠금을 깨면 안 된다.
 *
 * <p>이 클래스는 매니페스트를 <b>읽기만</b> 한다. 재생성은 사람이
 * {@code scripts/eval/locked_eval_manifest.py --write} 로 하고 그 diff 가 리뷰 대상이 된다.
 */
final class LockedEvalManifest {

    /** {@code key=value} 형태의 스칼라 항목. {@code case=} · {@code subgroup=} 줄은 뺀다. */
    static Map<String, String> scalars() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : LockedEvalSet.manifestText().lines().toList()) {
            if (line.isBlank() || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            String key = line.substring(0, line.indexOf('='));
            if (key.equals("case") || key.equals("subgroup")) {
                continue;
            }
            out.put(key, line.substring(line.indexOf('=') + 1).strip());
        }
        return out;
    }

    /** 케이스 id → 케이스 정규 문자열 해시. */
    static Map<String, String> caseHashes() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : LockedEvalSet.manifestText().lines().toList()) {
            if (!line.startsWith("case=")) {
                continue;
            }
            String body = line.substring("case=".length()).strip();
            int split = body.lastIndexOf(' ');
            out.put(body.substring(0, split), body.substring(split + 1));
        }
        return out;
    }

    /**
     * 주어진 케이스 목록과 매니페스트의 차이. 무엇이 추가·변경·삭제됐는지 그대로 돌려준다.
     *
     * @return 차이 설명 목록. 비어 있으면 매니페스트와 완전히 일치한다
     */
    static List<String> diff(List<LockedCase> cases) {
        Map<String, String> recorded = caseHashes();
        List<String> changed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (LockedCase c : cases) {
            seen.add(c.id());
            String hash = recorded.get(c.id());
            if (hash == null) {
                changed.add("추가됨: " + c.id());
            } else if (!hash.equals(LockedEvalSet.caseSha256(c))) {
                changed.add("변경됨: " + c.id());
            }
        }
        recorded.keySet().stream()
                .filter(id -> !seen.contains(id))
                .forEach(id -> changed.add("삭제됨: " + id));
        return List.copyOf(changed);
    }

    private LockedEvalManifest() {
    }
}
