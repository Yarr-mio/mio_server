package com.mio.ai.memory.ontology;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class OntologyValidatorTest {

    private CbtDistortionDefRepository distortionRepo;
    private EmotionDefRepository emotionRepo;
    private InterventionDefRepository interventionRepo;
    private OntologyValidator validator;

    @BeforeEach
    void setUp() {
        distortionRepo = mock(CbtDistortionDefRepository.class);
        emotionRepo = mock(EmotionDefRepository.class);
        interventionRepo = mock(InterventionDefRepository.class);
        validator = new OntologyValidator(distortionRepo, emotionRepo, interventionRepo);

        // 6종 왜곡 시드
        given(distortionRepo.existsById("all_or_nothing")).willReturn(true);
        given(distortionRepo.existsById("catastrophizing")).willReturn(true);
        given(distortionRepo.existsById("mind_reading")).willReturn(true);
        given(distortionRepo.existsById("fortune_telling")).willReturn(true);
        given(distortionRepo.existsById("emotional_reasoning")).willReturn(true);
        given(distortionRepo.existsById("overgeneralization")).willReturn(true);

        // 9종 감정 시드
        given(emotionRepo.existsById("sadness")).willReturn(true);
        given(emotionRepo.existsById("anxiety")).willReturn(true);
        given(emotionRepo.existsById("anger")).willReturn(true);
        given(emotionRepo.existsById("guilt")).willReturn(true);
        given(emotionRepo.existsById("shame")).willReturn(true);
        given(emotionRepo.existsById("loneliness")).willReturn(true);
        given(emotionRepo.existsById("hopelessness")).willReturn(true);
        given(emotionRepo.existsById("numbness")).willReturn(true);
        given(emotionRepo.existsById("frustration")).willReturn(true);

        // 개입 코드 일부
        given(interventionRepo.existsById("breathing_exercise")).willReturn(true);
        given(interventionRepo.existsById("cognitive_restructuring")).willReturn(true);
        given(interventionRepo.existsById("short_walk")).willReturn(true);
    }

    @Test
    @DisplayName("시드에 존재하는 왜곡 코드는 유효하다")
    void valid_distortion_codes_pass() {
        List<String> validCodes = List.of(
                "all_or_nothing", "catastrophizing", "mind_reading",
                "fortune_telling", "emotional_reasoning", "overgeneralization"
        );
        for (String code : validCodes) {
            assertThat(validator.isValidDistortionCode(code))
                    .as("왜곡 코드 '%s'는 유효해야 함", code)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("시드에 없는 왜곡 코드는 폐기된다")
    void unknown_distortion_code_is_rejected() {
        assertThat(validator.isValidDistortionCode("personalization")).isFalse();
        assertThat(validator.isValidDistortionCode("unknown_bias")).isFalse();
        assertThat(validator.isValidDistortionCode("")).isFalse();
        assertThat(validator.isValidDistortionCode(null)).isFalse();
    }

    @Test
    @DisplayName("시드에 존재하는 감정 코드 9종은 모두 유효하다")
    void valid_emotion_codes_pass() {
        List<String> validCodes = List.of(
                "sadness", "anxiety", "anger", "guilt", "shame",
                "loneliness", "hopelessness", "numbness", "frustration"
        );
        for (String code : validCodes) {
            assertThat(validator.isValidEmotionCode(code))
                    .as("감정 코드 '%s'는 유효해야 함", code)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("시드에 없는 감정 코드는 폐기된다")
    void unknown_emotion_code_is_rejected() {
        assertThat(validator.isValidEmotionCode("joy")).isFalse();
        assertThat(validator.isValidEmotionCode("fear")).isFalse();
    }

    @Test
    @DisplayName("모두 유효한 코드이면 allValid가 true다")
    void all_valid_codes_returns_all_valid_result() {
        var result = validator.validate("catastrophizing", "anxiety", "breathing_exercise");
        assertThat(result.allValid()).isTrue();
        assertThat(result.distortionValid()).isTrue();
        assertThat(result.emotionValid()).isTrue();
        assertThat(result.interventionValid()).isTrue();
    }

    @Test
    @DisplayName("왜곡 코드가 미등록이면 distortionValid가 false다")
    void invalid_distortion_code_makes_distortion_invalid() {
        var result = validator.validate("personalization", "anxiety", "breathing_exercise");
        assertThat(result.distortionValid()).isFalse();
        assertThat(result.allValid()).isFalse();
    }

    @Test
    @DisplayName("null 코드는 해당 필드 검증을 건너뛴다")
    void null_code_skips_validation() {
        var result = validator.validate(null, null, null);
        assertThat(result.allValid()).isTrue();
    }

    @Test
    @DisplayName("filterValidDistortionCodes는 미등록 코드를 필터링한다")
    void filter_valid_distortion_codes() {
        Set<String> input = Set.of("catastrophizing", "personalization", "all_or_nothing");
        given(distortionRepo.findCodesByCodeIn(input)).willReturn(Set.of("catastrophizing", "all_or_nothing"));

        Set<String> filtered = validator.filterValidDistortionCodes(input);

        assertThat(filtered).containsExactlyInAnyOrder("catastrophizing", "all_or_nothing");
        assertThat(filtered).doesNotContain("personalization");
    }

    @Test
    @DisplayName("filterValidDistortionCodes는 null 입력 시 빈 Set을 반환한다")
    void filter_valid_distortion_codes_null_returns_empty() {
        Set<String> result = validator.filterValidDistortionCodes(null);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("filterValidDistortionCodes는 빈 Set 입력 시 빈 Set을 반환한다")
    void filter_valid_distortion_codes_empty_returns_empty() {
        Set<String> result = validator.filterValidDistortionCodes(Set.of());
        assertThat(result).isEmpty();
    }
}
