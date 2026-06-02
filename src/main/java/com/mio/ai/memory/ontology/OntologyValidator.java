package com.mio.ai.memory.ontology;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * ExtractorLLM 출력의 distortion/emotion/intervention 코드가 시드에 존재하는지 검증.
 * 시드 외 코드는 폐기 (§12.8 OntologyValidator).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OntologyValidator {

    private final CbtDistortionDefRepository distortionRepo;
    private final EmotionDefRepository emotionRepo;
    private final InterventionDefRepository interventionRepo;

    public boolean isValidDistortionCode(String code) {
        if (code == null || code.isBlank()) return false;
        return distortionRepo.existsById(code);
    }

    public boolean isValidEmotionCode(String code) {
        if (code == null || code.isBlank()) return false;
        return emotionRepo.existsById(code);
    }

    public boolean isValidInterventionCode(String code) {
        if (code == null || code.isBlank()) return false;
        return interventionRepo.existsById(code);
    }

    public Set<String> filterValidDistortionCodes(Set<String> codes) {
        Set<String> allCodes = distortionRepo.findAll().stream()
                .map(CbtDistortionDef::getCode)
                .collect(Collectors.toSet());
        return codes.stream()
                .filter(allCodes::contains)
                .collect(Collectors.toSet());
    }

    public Set<String> filterValidEmotionCodes(Set<String> codes) {
        Set<String> allCodes = emotionRepo.findAll().stream()
                .map(EmotionDef::getCode)
                .collect(Collectors.toSet());
        return codes.stream()
                .filter(allCodes::contains)
                .collect(Collectors.toSet());
    }

    public OntologyValidationResult validate(String distortionCode, String emotionCode, String interventionCode) {
        boolean distortionValid = distortionCode == null || isValidDistortionCode(distortionCode);
        boolean emotionValid = emotionCode == null || isValidEmotionCode(emotionCode);
        boolean interventionValid = interventionCode == null || isValidInterventionCode(interventionCode);

        if (!distortionValid) {
            log.warn("OntologyValidator: unknown distortion code '{}' — discarded", distortionCode);
        }
        if (!emotionValid) {
            log.warn("OntologyValidator: unknown emotion code '{}' — discarded", emotionCode);
        }
        if (!interventionValid) {
            log.warn("OntologyValidator: unknown intervention code '{}' — discarded", interventionCode);
        }

        return new OntologyValidationResult(distortionValid, emotionValid, interventionValid);
    }

    public record OntologyValidationResult(
            boolean distortionValid,
            boolean emotionValid,
            boolean interventionValid
    ) {
        public boolean allValid() {
            return distortionValid && emotionValid && interventionValid;
        }
    }
}
