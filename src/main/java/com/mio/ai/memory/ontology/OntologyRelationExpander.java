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

        List<String> recommended = definitionFor(currentDistortionCode)
                .map(CbtDistortionDef::getRecommendedActions)
                .orElse(List.of());
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
        try {
            Set<String> registered = distortionRepository.findCodesByCodeIn(candidates);
            if (registered == null || registered.isEmpty()) {
                return Set.of();
            }
            return candidates.stream().filter(registered::contains)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            log.warn("Ontology cooccurrence lookup failed; skipping relation expansion", e);
            return Set.of();
        }
    }

    private Optional<CbtDistortionDef> definitionFor(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return distortionRepository.findById(code);
        } catch (Exception e) {
            log.warn("Ontology relation lookup failed for distortionCode={}; skipping", code, e);
            return Optional.empty();
        }
    }
}
