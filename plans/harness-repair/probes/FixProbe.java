import com.mio.ai.crisis.*;
import java.util.List;

/** 수정안 A 검증: 있지/없지를 마커에서 제거 + -지/진/지는/지가 + 않 표지는 UNKNOWN 확정.
 *  프로덕션 CrisisFlowStateMachine 을 그대로 써서 최종 라우팅까지 비교한다. */
public class FixProbe {
    // 현행에서 "있지"/"없지" 만 제거한 목록
    static final List<String> YES = List.of("있어","있습","있다","있음","있네","있죠","있는데","그래","맞아","정했","준비","구했","yes");
    static final List<String> YESP = List.of("네","예","응");
    static final List<String> NO  = List.of("없어","없습","없다","없음","없네","없죠","없는데","아니요","아니에요","아니야","아닙니다","아닌데","no");
    static final List<String> NOP = List.of("아니");
    // 신규: 부정 보조용언 -지 않다 계열 (비한글 제거 후 형태)
    static final List<String> NEG = List.of("지않","진않","지는않","지가않","지도않","질않");

    static CrisisAnswer fixedParse(String raw) {
        if (raw == null || raw.isBlank()) return CrisisAnswer.UNKNOWN;
        String n = raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^가-힣a-z]", "");
        for (String m : NEG) if (n.contains(m)) return CrisisAnswer.UNKNOWN;  // fail-closed
        boolean yes = any(n, YES) || pre(n, YESP);
        boolean no  = any(n, NO)  || pre(n, NOP);
        if (yes == no) return CrisisAnswer.UNKNOWN;
        return yes ? CrisisAnswer.YES : CrisisAnswer.NO;
    }
    static boolean any(String v, List<String> ms){ for(String m:ms) if(v.contains(m)) return true; return false; }
    static boolean pre(String v, List<String> ps){ for(String p:ps) if(v.startsWith(p)) return true; return false; }

    record C(String stage, String text, CrisisAnswer truth) {}

    public static void main(String[] a) {
        CrisisAnswerParser prod = new CrisisAnswerParser();
        CrisisFlowStateMachine sm = new CrisisFlowStateMachine();
        C[] cs = {
            new C("IMMEDIATE_SUPPORT","있지 않아요",CrisisAnswer.NO),
            new C("IMMEDIATE_SUPPORT","믿을 만한 사람이 있지 않아요",CrisisAnswer.NO),
            new C("IMMEDIATE_SUPPORT","그런 사람은 있지 않습니다",CrisisAnswer.NO),
            new C("IMMEDIATE_SUPPORT","딱히 있지 않네요",CrisisAnswer.NO),
            new C("IMMEDIATE_SUPPORT","연락할 사람이 있지가 않아요",CrisisAnswer.NO),
            new C("IMMEDIATE_SUPPORT","있진 않아요",CrisisAnswer.NO),
            new C("IMMEDIATE_SUPPORT","없어요",CrisisAnswer.NO),
            new C("IMMEDIATE_SUPPORT","아니요",CrisisAnswer.NO),
            new C("IMMEDIATE_SUPPORT","아무도 없어요",CrisisAnswer.NO),
            new C("IMMEDIATE_SUPPORT","네 있어요",CrisisAnswer.YES),
            new C("IMMEDIATE_SUPPORT","있습니다",CrisisAnswer.YES),
            new C("IMMEDIATE_SUPPORT","한 명 있어요",CrisisAnswer.YES),
            new C("CURRENT_INTENT","죽고 싶은 생각이 있지 않아요",CrisisAnswer.NO),
            new C("CURRENT_INTENT","네 있어요",CrisisAnswer.YES),
            new C("PLAN","계획은 세우지 않았어요",CrisisAnswer.NO),
            new C("PLAN","정했어요",CrisisAnswer.YES),
            new C("MEANS","정해두지 않았어요",CrisisAnswer.NO),
            new C("MEANS","구했어요",CrisisAnswer.YES),
        };
        System.out.printf("%-19s %-26s %-5s | %-8s %-11s | %-8s %-11s%n",
            "STAGE","INPUT","정답","현행","현행 라우팅","수정안","수정안 라우팅");
        System.out.println("-".repeat(108));
        int revProd=0, revFix=0;
        for (C c : cs) {
            CrisisFlowStage st = CrisisFlowStage.valueOf(c.stage());
            CrisisAnswer p = prod.parse(c.text());
            CrisisAnswer f = fixedParse(c.text());
            String rp = sm.next(st, p).nextStage().name();
            String rf = sm.next(st, f).nextStage().name();
            boolean revP = c.truth()==CrisisAnswer.NO && p==CrisisAnswer.YES;
            boolean revF = c.truth()==CrisisAnswer.NO && f==CrisisAnswer.YES;
            if(revP) revProd++;
            if(revF) revFix++;
            System.out.printf("%-19s %-26s %-5s | %-8s %-11s | %-8s %-11s %s%n",
                c.stage(), c.text(), c.truth(), p, rp, f, rf,
                revP&&!revF?"← 교정":(revF?"← 여전히 역전":""));
        }
        System.out.println("-".repeat(108));
        System.out.printf("위험 역전  현행 %d건  →  수정안 %d건   (총 %d건)%n", revProd, revFix, cs.length);
    }
}
