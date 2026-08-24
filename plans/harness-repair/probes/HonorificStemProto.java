import java.util.*;
import java.util.regex.Pattern;

/** 경어 어간을 기존 기계에 넣기만 하면 되는지 프로토타입. 프로덕션 코드 미변경. */
public class Proto {
    // 현행 + 경어 추가분
    static final List<String> YES_M = List.of(
        "있어","있습","있다","있음","있네","있죠","있지","있는데","그래","맞아","정했","준비","구했","yes",
        /* 경어 */ "계세","계십","계셔","계신","있으세","있으십");
    static final List<String> YES_P = List.of("네","예","응");
    static final List<String> NO_M = List.of(
        "없어","없습","없다","없음","없네","없죠","없지","없는데",
        "아니요","아니에요","아니야","아닙니다","아닌데","no",
        /* 경어 */ "없으세","없으십","안계세","안계십","안계셔");
    static final List<String> NO_P = List.of("아니");
    // 어간에 경어 추가 → 차단 정규식이 -지 않/안/아니/못 을 자동 처리
    static final Pattern BLOCK = Pattern.compile(
        "(?:있|없|계시|있으시|없으시)(?:지(?:는|가|도|를)?|진|질)(?:않|안|아니|못)"
        + "|(?:있|없|계시)지만");
    static final Pattern NON_ANSWER = Pattern.compile("[^가-힣a-z]");

    static String parse(String raw) {
        if (raw == null || raw.isBlank()) return "UNKNOWN";
        String n = NON_ANSWER.matcher(raw.toLowerCase(Locale.ROOT)).replaceAll("");
        if (BLOCK.matcher(n).find()) return "UNKNOWN";
        boolean yes = any(n, YES_M) || pre(n, YES_P);
        boolean no  = any(n, NO_M)  || pre(n, NO_P);
        if (yes == no) return "UNKNOWN";
        return yes ? "YES" : "NO";
    }
    static boolean any(String v, List<String> m){ for(String x:m) if(v.contains(x)) return true; return false; }
    static boolean pre(String v, List<String> m){ for(String x:m) if(v.startsWith(x)) return true; return false; }

    public static void main(String[] a) {
        String[][] cs = {
          // 경어 YES
          {"계세요","YES"},{"계십니다","YES"},{"옆에 계세요","YES"},{"어머니가 계세요","YES"},
          {"있으세요","YES"},{"있으십니다","YES"},{"언니가 계셔서 괜찮아요","YES"},{"지금 같이 계세요","YES"},
          // 경어 NO
          {"안 계세요","NO"},{"계시지 않아요","NO"},{"없으십니다","NO"},{"없으세요","NO"},
          {"아무도 안 계세요","NO"},{"곁에 계신 분이 없어요","NO"},{"연락드릴 분이 안 계세요","NO"},
          {"부모님도 안 계세요","NO"},{"계시지 못해요","NO"},{"안 계십니다","NO"},
          // 기존 평서 — 회귀 없어야 함
          {"있어요","YES"},{"있습니다","YES"},{"네 있어요","YES"},{"한 명 있어요","YES"},
          {"있지","YES"},{"있지요","YES"},{"없어요","NO"},{"없습니다","NO"},{"아니요","NO"},
          {"아무도 없어요","NO"},{"그런 사람 없어요","NO"},
          // 기존 차단 형태 — 유지되어야 함
          {"있지 않아요","UNKNOWN"},{"있지 안아요","UNKNOWN"},{"있지 못해요","UNKNOWN"},
          {"있지만 연락은 안 해요","UNKNOWN"},{"없지 않아요","UNKNOWN"},
        };
        int ok=0, rev=0, unk=0;
        System.out.printf("%-30s %-9s %-9s %s%n","답변","정답","결과","");
        System.out.println("-".repeat(64));
        for (String[] c : cs) {
            String got = parse(c[0]);
            String v;
            if (got.equals(c[1])) { v=""; ok++; }
            else if (got.equals("UNKNOWN")) { v="UNKNOWN(보수화)"; unk++; }
            else { v="★위험역전"; rev++; }
            System.out.printf("%-30s %-9s %-9s %s%n", c[0], c[1], got, v);
        }
        System.out.println("-".repeat(64));
        System.out.printf("일치 %d · UNKNOWN 보수화 %d · ★역전 %d / 총 %d%n", ok, unk, rev, cs.length);
    }
}
