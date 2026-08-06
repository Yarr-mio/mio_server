package com.mio.ai.memory.ontology;

import com.mio.ai.policy.InterventionHints;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 검증된 현재 왜곡의 시드 관계만 검색 후보와 기존 개입 후보의 우선순위에 반영한다.
 * 동반 왜곡은 현재 진단이나 WorkingMemory 상태로 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OntologyRelationExpander {

    private final CbtDistortionDefRepository distortionRepository;

    /**
     * 동반 왜곡 코드로 확장한다.
     *
     * <p><b>조회 실패를 삼키지 않는다</b> (이슈 #364). 확장 결과가 비면 GRAPH_DISTORTION
     * 소스는 아무것도 조회하지 않으므로, 실패를 빈 집합으로 바꾸면 "동반 왜곡이 원래 없음"
     * 과 구별되지 않는다. 검색기에서 걷어낸 것과 같은 결함이 한 단계 앞에 남는다.
     * 실패 판정은 호출부({@code ContextPreWarmer})가 소스를 아는 자리에서 한다.
     */
    public Set<String> expandCooccurringCodes(String currentDistortionCode) {
        return definitionFor(currentDistortionCode)
                .map(CbtDistortionDef::getCooccurCodes)
                .map(this::registeredCodes)
                .orElseGet(Set::of);
    }

    /** 정책과 금기 필터가 이미 승인한 후보만 시드 권장 행동 순서로 좁힌다. */
    public InterventionHints rerankApprovedHints(InterventionHints hints, String currentDistortionCode) {
        if (hints == null) {
            return InterventionHints.empty();
        }
        if (hints.suggestedCodes() == null || hints.suggestedCodes().isEmpty()) {
            return hints;
        }

        // 재정렬은 실패해도 기존 안전 후보를 그대로 쓰면 된다. 그래서 여기서 잡는다.
        List<String> recommended;
        try {
            recommended = definitionFor(currentDistortionCode)
                    .map(CbtDistortionDef::getRecommendedActions)
                    .orElse(List.of());
        } catch (Exception e) {
            log.warn("Ontology rerank lookup failed for distortionCode={}; keeping approved order",
                    currentDistortionCode, e);
            return hints;
        }
        if (recommended == null || recommended.isEmpty()) {
            return hints;
        }

        Set<String> approved = new LinkedHashSet<>(hints.suggestedCodes());
        List<String> matched = recommended.stream()
                .filter(approved::contains)
                .distinct()
                .toList();
        // 관계가 맞지 않는다고 기존 안전 후보를 비우지 않는다.
        if (matched.isEmpty()) {
            return hints;
        }
        return new InterventionHints(matched, hints.avoidCodes(), hints.targetDistortionCode());
    }

    private Set<String> registeredCodes(List<String> candidateCodes) {
        if (candidateCodes == null || candidateCodes.isEmpty()) {
            return Set.of();
        }
        Set<String> candidates = candidateCodes.stream()
                .filter(code -> code != null && !code.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (candidates.isEmpty()) {
            return Set.of();
        }
        // 조회 실패를 여기서 빈 집합으로 바꾸지 않는다 — 호출부가 실패로 기록해야 한다.
        Set<String> registered = distortionRepository.findCodesByCodeIn(candidates);
        if (registered == null || registered.isEmpty()) {
            return Set.of();
        }
        return candidates.stream().filter(registered::contains)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 정의 조회. 실패를 삼키지 않는다 (이슈 #364).
     *
     * <p>{@link #rerankApprovedHints} 는 실패해도 후보를 그대로 두면 되므로 자기 자리에서
     * 잡는다. 검색 확장은 잡지 않고 올려서 소스 실패로 기록되게 한다. 실패 정책을 호출부마다
     * 다르게 두는 것이 목적이다 — 한곳에서 일괄로 삼키면 둘 중 하나는 반드시 틀린다.
     */
    private Optional<CbtDistortionDef> definitionFor(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return distortionRepository.findById(code);
    }
}
