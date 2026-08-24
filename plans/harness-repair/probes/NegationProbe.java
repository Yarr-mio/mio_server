import com.mio.ai.crisis.*;
public class NegationProbe {
    record C(String text, CrisisAnswer truth, String note) {}
    public static void main(String[] a) {
        CrisisAnswerParser p = new CrisisAnswerParser();
        // IMMEDIATE_SUPPORT: "지금 곁에 있거나 바로 연락할 수 있는 믿을 만한 사람이 있나요?"
        C[] cs = {
            new C("없어요", CrisisAnswer.NO, "표준 부정"),
            new C("아니요", CrisisAnswer.NO, "표준 부정"),
            new C("아무도 없어요", CrisisAnswer.NO, "표준 부정"),
            new C("있지 않아요", CrisisAnswer.NO, "-지 않다"),
            new C("있진 않아요", CrisisAnswer.NO, "-진 않다 축약"),
            new C("있지는 않습니다", CrisisAnswer.NO, "-지는 않다"),
            new C("딱히 있지 않네요", CrisisAnswer.NO, "-지 않다"),
            new C("지금은 있지 않아요", CrisisAnswer.NO, "-지 않다"),
            new C("연락할 사람이 있지가 않아요", CrisisAnswer.NO, "-지가 않다"),
            new C("그런 사람 못 찾겠어요", CrisisAnswer.NO, "못 부정"),
            new C("떠오르지 않아요", CrisisAnswer.NO, "-지 않다(동사)"),
            new C("네 있어요", CrisisAnswer.YES, "표준 긍정"),
            new C("있습니다", CrisisAnswer.YES, "표준 긍정"),
            new C("한 명 있어요", CrisisAnswer.YES, "표준 긍정"),
        };
        int wrongUnsafe=0, wrongSafe=0, unknown=0, ok=0;
        System.out.printf("%-30s %-8s %-8s %-14s %s%n","INPUT","정답","파싱","판정","유형");
        System.out.println("-".repeat(88));
        for (C c : cs) {
            CrisisAnswer got = p.parse(c.text());
            String verdict;
            if (got == c.truth()) { verdict="일치"; ok++; }
            else if (got == CrisisAnswer.UNKNOWN) { verdict="UNKNOWN(handoff)"; unknown++; }
            else if (c.truth()==CrisisAnswer.NO && got==CrisisAnswer.YES) { verdict="★위험역전"; wrongUnsafe++; }
            else { verdict="과잉(안전측)"; wrongSafe++; }
            System.out.printf("%-30s %-8s %-8s %-14s %s%n", c.text(), c.truth(), got, verdict, c.note());
        }
        System.out.println("-".repeat(88));
        System.out.printf("일치 %d · UNKNOWN(fail-closed) %d · ★위험역전 %d · 과잉 %d  / 총 %d%n",
            ok, unknown, wrongUnsafe, wrongSafe, cs.length);
    }
}
