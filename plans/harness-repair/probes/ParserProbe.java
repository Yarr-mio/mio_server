import com.mio.ai.crisis.*;

public class ParserProbe {
    public static void main(String[] a) {
        CrisisAnswerParser p = new CrisisAnswerParser();
        CrisisFlowStateMachine sm = new CrisisFlowStateMachine();

        String[][] cases = {
            // 질문 단계, 사용자 답변, 사람이 읽는 의미
            {"IMMEDIATE_SUPPORT", "있지 않아요",            "없다 (지원자 없음)"},
            {"IMMEDIATE_SUPPORT", "믿을 만한 사람이 있지 않아요", "없다"},
            {"IMMEDIATE_SUPPORT", "그런 사람은 있지 않습니다",   "없다"},
            {"IMMEDIATE_SUPPORT", "없어요",                "없다 (정상 표현)"},
            {"IMMEDIATE_SUPPORT", "아니요",                "없다 (정상 표현)"},
            {"IMMEDIATE_SUPPORT", "네",                   "있다"},
            {"CURRENT_INTENT",    "죽고 싶은 생각이 있지 않아요", "없다"},
            {"CURRENT_INTENT",    "그런 생각 들지 않아요",      "없다"},
            {"PLAN",              "계획은 세우지 않았어요",     "없다"},
            {"PLAN",              "준비 안 했어요",           "없다"},
            {"MEANS",             "정해두지 않았어요",         "없다"},
            {"IMMEDIATE_SUPPORT", "연락할 사람이 없지 않아요",   "있다 (이중부정)"},
        };

        System.out.printf("%-20s %-28s %-18s %-9s %-12s %s%n",
            "STAGE","INPUT","사람이 읽는 의미","PARSED","다음 단계","상태");
        System.out.println("-".repeat(115));
        for (String[] c : cases) {
            CrisisAnswer ans = p.parse(c[1]);
            CrisisFlowStage stage = CrisisFlowStage.valueOf(c[0]);
            CrisisFlowTransition t = sm.next(stage, ans);
            System.out.printf("%-20s %-28s %-18s %-9s %-12s %s%n",
                c[0], c[1], c[2], ans, t.nextStage(), t.status());
        }
    }
}
