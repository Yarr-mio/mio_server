package com.mio.ai.qa.correction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[QA] 정정 시험 세트 집계·경고")
class CorrectionEvalSetSummaryTest {

    private static CorrectionEvalCase correction(String id, String type, String strength, int turnPairs) {
        List<CorrectionEvalCase.Turn> turns = new ArrayList<>();
        for (int i = 0; i < turnPairs; i++) {
            turns.add(new CorrectionEvalCase.Turn("USER", "사용자 발화 " + i));
            turns.add(new CorrectionEvalCase.Turn("ASSISTANT", "AI 발화 " + i));
        }
        turns.add(new CorrectionEvalCase.Turn("USER", "정정 발화"));
        return new CorrectionEvalCase(id, type, strength, true, turns);
    }

    private static CorrectionEvalCase control(String id, boolean adviceRequested) {
        return new CorrectionEvalCase(id, "control", null, false, adviceRequested,
                List.of(new CorrectionEvalCase.Turn("USER", "대조군 발화")));
    }

    @Test
    @DisplayName("작은 세트는 종류·대조군·조언 요청·강도·길이 부족을 경고한다")
    void warnsWhenSetIsSmall() {
        CorrectionEvalSet set = new CorrectionEvalSet("v", List.of(
                correction("E-1", "fact", "strong", 1),
                correction("E-2", "fact", "strong", 1),
                control("N-1", false)));

        CorrectionEvalSetSummary summary = CorrectionEvalSetSummary.of(set);

        assertThat(summary.total()).isEqualTo(3);
        assertThat(summary.corrections()).isEqualTo(2);
        assertThat(summary.controls()).isEqualTo(1);
        assertThat(summary.warnings())
                .anyMatch(w -> w.contains("정정 종류 emotion"))
                .anyMatch(w -> w.contains("강도 weak"))
                .anyMatch(w -> w.contains("대조군이 1건"))
                .anyMatch(w -> w.contains("조언 요청 대조군이 0건"));
    }

    @Test
    @DisplayName("모든 케이스의 대화 길이가 같으면 다양성 부족을 경고한다")
    void warnsWhenAllCasesHaveTheSameLength() {
        CorrectionEvalSet set = new CorrectionEvalSet("v", List.of(
                correction("E-1", "fact", "strong", 1),
                correction("E-2", "emotion", "weak", 1)));

        assertThat(CorrectionEvalSetSummary.of(set).warnings())
                .anyMatch(w -> w.contains("대화 길이가 한 가지뿐"));
    }

    @Test
    @DisplayName("권장 규모를 채우면 경고가 없다")
    void noWarningsWhenSetMeetsGuidance() {
        List<CorrectionEvalCase> cases = new ArrayList<>();
        String[] types = {"fact", "emotion", "advice_refusal", "soft_disagreement", "restatement"};
        String[] strengths = {"strong", "medium", "weak"};
        int n = 0;
        for (String type : types) {
            for (int i = 0; i < 6; i++) {
                cases.add(correction("E-" + (n++), type, strengths[i % 3], 1 + (i % 2)));
            }
        }
        for (int i = 0; i < 20; i++) {
            cases.add(control("N-" + i, i < 6));
        }

        CorrectionEvalSetSummary summary = CorrectionEvalSetSummary.of(new CorrectionEvalSet("v", cases));

        assertThat(summary.warnings()).isEmpty();
        assertThat(summary.adviceRequested()).isEqualTo(6);
    }

    @Test
    @DisplayName("출력에는 건수 분포와 해시만 있고 케이스 본문·id 는 없다")
    void renderExposesNoCaseContent() {
        CorrectionEvalSet set = new CorrectionEvalSet("v", List.of(
                correction("E-SECRET-ID", "fact", "strong", 1), control("N-SECRET-ID", false)));

        String rendered = CorrectionEvalSetSummary.of(set).render("abc123");

        assertThat(rendered).contains("총 2건", "sha256: abc123", "fact=1");
        assertThat(rendered).doesNotContain("SECRET", "정정 발화", "사용자 발화", "AI 발화", "대조군 발화");
    }
}
