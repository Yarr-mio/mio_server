package com.mio.ai.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.OpenAiLlmClient;
import com.mio.ai.memory.consolidation.ExtractorLlmClient;
import com.mio.ai.memory.consolidation.ExtractorResult;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.*;

import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@DisplayName("[QA] ExtractorLLM episodeType 스케일 테스트 (~1000 시나리오)")
@Tag("llm-integration")
class ExtractorLlmScaleTest {

    private static ExtractorLlmClient extractor;

    record Case(String id, String summary, String expected, String cat) {}
    record Result(String id, String expected, String actual, boolean passed, String cat) {}

    @BeforeAll
    static void setUp() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && apiKey.startsWith("sk-"),
                "OPENAI_API_KEY 미설정 또는 placeholder — LLM 통합 테스트 skip");
        extractor = new ExtractorLlmClient(
                new OpenAiLlmClient(apiKey, HttpClient.newHttpClient(), new ObjectMapper()),
                new ObjectMapper()
        );
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    @DisplayName("~1000 시나리오 병렬 배치 분류 검증")
    void validate_scale() throws Exception {
        List<Case> cases = buildAll();
        System.out.printf("%n총 시나리오: %d건 실행 시작 (병렬 3)%n", cases.size());

        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<Result>> futures = cases.stream()
                .map(c -> pool.submit(() -> runCase(c)))
                .toList();

        List<Result> results = new ArrayList<>(cases.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get(90, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                Case c = cases.get(i);
                results.add(new Result(c.id(), c.expected(), "TIMEOUT", false, c.cat()));
            } catch (Exception e) {
                Case c = cases.get(i);
                results.add(new Result(c.id(), c.expected(), "ERROR", false, c.cat()));
            }
            if ((i + 1) % 100 == 0) {
                long p = results.stream().filter(Result::passed).count();
                System.out.printf("  진행: %d/%d (현재 pass: %d)%n", i + 1, cases.size(), p);
            }
        }
        pool.shutdown();

        report(results, cases.size());

        long pass = results.stream().filter(Result::passed).count();
        double rate = (double) pass / results.size();
        Assertions.assertThat(rate)
                .as("pass rate >= 95%% (actual: %.1f%%)", rate * 100)
                .isGreaterThanOrEqualTo(0.95);
    }

    private Result runCase(Case c) {
        try {
            ExtractorResult r = extractor.extract(c.summary());
            String actual = r.episodeType() != null ? r.episodeType() : "null";
            return new Result(c.id(), c.expected(), actual, c.expected().equals(actual), c.cat());
        } catch (Exception e) {
            return new Result(c.id(), c.expected(), "ERROR", false, c.cat());
        }
    }

    private void report(List<Result> results, int total) {
        Map<String, List<Result>> byCat = results.stream()
                .collect(Collectors.groupingBy(Result::cat));
        System.out.printf("%n%n═══════════════════════════════════════════════════%n");
        System.out.printf("  ExtractorLLM 스케일 테스트 결과%n");
        System.out.printf("═══════════════════════════════════════════════════%n");
        long totalPass = results.stream().filter(Result::passed).count();
        System.out.printf("  전체: %d / %d  (%.1f%%)%n%n",
                totalPass, total, totalPass * 100.0 / total);

        List<String> catOrder = List.of(
                "normal_regular", "normal_support_only", "normal_cbt_success",
                "hard_cbt_partial", "hard_ambiguous", "boundary_edge", "real_failures");
        Map<String, String> catLabel = Map.of(
                "normal_regular", "정상-regular",
                "normal_support_only", "정상-support_only",
                "normal_cbt_success", "정상-cbt_success",
                "hard_cbt_partial", "어려운-cbt_partial",
                "hard_ambiguous", "경계-ambiguous",
                "boundary_edge", "경계/엣지",
                "real_failures", "실제실패패턴");
        for (String cat : catOrder) {
            List<Result> catResults = byCat.getOrDefault(cat, List.of());
            if (catResults.isEmpty()) continue;
            long p = catResults.stream().filter(Result::passed).count();
            System.out.printf("  %-24s %3d / %3d  (%.1f%%)%n",
                    catLabel.getOrDefault(cat, cat) + ":",
                    p, catResults.size(), p * 100.0 / catResults.size());
        }

        List<Result> failures = results.stream().filter(r -> !r.passed()).toList();
        System.out.printf("%n  총 실패: %d건%n", failures.size());
        if (!failures.isEmpty()) {
            System.out.println("  실패 목록 (최대 50건):");
            failures.stream().limit(50).forEach(f ->
                    System.out.printf("    [%s] expected=%-14s actual=%-14s  cat=%s%n",
                            f.id(), f.expected(), f.actual(), f.cat()));
        }
        System.out.printf("═══════════════════════════════════════════════════%n");
    }

    private List<Case> buildAll() {
        List<Case> all = new ArrayList<>();
        all.addAll(normalRegular());
        all.addAll(normalSupportOnly());
        all.addAll(normalCbtSuccess());
        all.addAll(hardCbtPartial());
        all.addAll(hardAmbiguous());
        all.addAll(boundaryEdge());
        all.addAll(realFailures());
        return all;
    }

    // ───────────────────────────────────────────────
    // 정상-regular (200건): SC-R-001 ~ SC-R-200
    // ───────────────────────────────────────────────
    private List<Case> normalRegular() {
        String[] topics = {
            "독서", "운동 루틴", "요리 레시피", "여행 계획", "영화 추천",
            "음악 취향", "게임", "반려동물 돌봄", "외국어 학습", "자격증 준비",
            "주식·재테크 정보", "인테리어 아이디어", "패션 코디", "식물 가꾸기",
            "카페 탐방", "등산 코스", "수영 기초", "사진 촬영 팁", "베이킹",
            "자전거 라이딩"
        };
        String[] aiResponses = {
            "AI는 관련 정보와 추천 목록을 구체적으로 안내했다.",
            "AI는 입문자용 팁과 단계별 방법을 설명했다.",
            "AI는 사용자의 취향에 맞는 다양한 선택지를 제안했다.",
            "AI는 경험담과 함께 실용적인 정보를 공유했다.",
            "AI는 비용·시간 측면의 비교 분석 정보를 제공했다.",
            "AI는 주의사항과 준비물 목록을 안내했다.",
            "AI는 온라인 리소스와 커뮤니티를 소개했다.",
            "AI는 자주 묻는 질문과 답변 형식으로 설명했다.",
            "AI는 최신 트렌드와 인기 옵션을 소개했다.",
            "AI는 사용자가 요청한 세부 정보를 친절히 제공했다."
        };
        String[] contexts = {
            "사용자는 처음 시작하는 입장에서 기초 정보를 물어봤다.",
            "사용자는 이미 경험이 있으며 더 발전하고 싶다고 했다.",
            "사용자는 주말 활용 방법으로 관심을 표명했다.",
            "사용자는 친구와 함께 즐길 수 있는 활동을 찾고 있었다.",
            "사용자는 비용 대비 효과적인 방법을 알고 싶어했다."
        };
        String[] endings = {
            "대화 전반에 감정적 고통이나 어려움 호소는 전혀 없었으며 가벼운 정보 교환으로 마무리됐다.",
            "사용자는 유용한 정보를 얻었다고 했으며 편안한 분위기로 종료됐다.",
            "인지 왜곡 탐색이나 소크라테스 질문 없이 순수한 정보 교류 세션이었다.",
            "사용자는 감사 인사를 전하고 즐거운 분위기로 대화를 마쳤다.",
            "CBT 개입 없이 일상적인 대화로 마무리됐다."
        };

        List<Case> cases = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String topic = topics[i % topics.length];
            String ctx = contexts[i % contexts.length];
            String aiResp = aiResponses[i % aiResponses.length];
            String ending = endings[i % endings.length];
            String summary = ctx + " 주제는 " + topic + "이었다. " + aiResp + " " + ending;
            cases.add(new Case("SC-R-%03d".formatted(i + 1), summary, "regular", "normal_regular"));
        }
        return cases;
    }

    // ───────────────────────────────────────────────
    // 정상-support_only (150건): SC-S-001 ~ SC-S-150
    // ───────────────────────────────────────────────
    private List<Case> normalSupportOnly() {
        String[] situations = {
            "중요한 시험에서 기대보다 낮은 점수를 받아 실망감을 느꼈다",
            "친한 친구와 오해로 인한 갈등이 생겨 마음이 무거웠다",
            "오랜 연애가 끝나 외로움과 슬픔을 느꼈다",
            "직장에서 과도한 업무로 인해 지치고 번아웃 상태였다",
            "가족과의 갈등으로 집에 있는 것이 불편하다고 했다",
            "취업 준비를 오래 했지만 계속 불합격 통보를 받아 좌절했다",
            "건강검진 결과가 걱정돼 불안함을 호소했다",
            "경제적인 어려움으로 스트레스를 받고 있었다",
            "새로운 환경에 적응하지 못해 고립감을 느꼈다",
            "자신감이 많이 떨어져 있고 자존감이 낮다고 했다"
        };
        String[] userEmotions = {
            "사용자는 힘들고 지쳐있다고 했다",
            "사용자는 슬프고 외롭다고 표현했다",
            "사용자는 불안하고 걱정이 많다고 했다",
            "사용자는 답답하고 막막하다고 했다",
            "사용자는 위로가 필요하다고 했다"
        };
        String[] aiEmpathy = {
            "AI는 사용자의 감정을 충분히 공감하고 위로의 말을 건넸다. 소크라테스 질문이나 인지 왜곡 탐색은 전혀 없었다.",
            "AI는 사용자의 이야기를 경청하며 '많이 힘드셨겠어요'라고 공감했다. 인지 개입은 시도하지 않았다.",
            "AI는 감정을 있는 그대로 받아들이고 지지 표현을 했다. CBT 기법은 사용하지 않았다.",
            "AI는 따뜻한 말로 사용자를 위로했고 판단 없이 경청했다. 소크라테스 질문은 없었다.",
            "AI는 공감과 정서적 지지에 집중했으며 인지 재구성 시도는 없었다."
        };
        String[] endings = {
            "사용자는 털어놓고 나서 조금 나아진 것 같다고 했다. 세션 전체에서 인지적 개입은 없었다.",
            "사용자는 들어줘서 고맙다고 했고 대화는 공감과 경청으로 마무리됐다.",
            "인지 왜곡 탐색이나 소크라테스 질문 없이 감정 지지 세션으로 종료됐다.",
            "사용자는 감정을 충분히 표현했고 AI의 공감으로 세션이 마무리됐다.",
            "전 세션 동안 AI는 경청과 공감만 했으며 어떠한 CBT 개입도 없었다."
        };

        List<Case> cases = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            String sit = situations[i % situations.length];
            String emo = userEmotions[i % userEmotions.length];
            String ai = aiEmpathy[i % aiEmpathy.length];
            String end = endings[i % endings.length];
            String summary = "사용자는 " + sit + "고 이야기했다. " + emo + ". " + ai + " " + end;
            cases.add(new Case("SC-S-%03d".formatted(i + 1), summary, "support_only", "normal_support_only"));
        }
        return cases;
    }

    // ───────────────────────────────────────────────
    // 정상-cbt_success (200건): SC-C-001 ~ SC-C-200
    // ───────────────────────────────────────────────
    private List<Case> normalCbtSuccess() {
        String[] distortions = {
            "전부 아니면 전무(이분법적) 사고",
            "과잉 일반화",
            "파국화",
            "독심술(마음 읽기)",
            "자기 비난",
            "감정적 추론",
            "최소화/극대화",
            "당위적 사고(must/should)",
            "개인화",
            "선택적 주의"
        };
        String[] socraticQuestions = {
            "AI는 '정말 항상 그런 결과가 나왔나요? 예외는 없었나요?'라고 소크라테스 질문을 했다.",
            "AI는 '그 생각을 뒷받침하는 증거와 반대하는 증거는 무엇인가요?'라고 물었다.",
            "AI는 '가장 나쁜 경우, 가장 좋은 경우, 현실적인 결과는 무엇일까요?'라고 탐색했다.",
            "AI는 '친한 친구가 같은 상황이라면 어떻게 말해줄 것 같나요?'라고 인지 재구성을 시도했다.",
            "AI는 '그 생각이 사실이라는 것을 어떻게 알 수 있나요?'라고 물었다.",
            "AI는 '10년 후에 이 상황을 돌아본다면 어떻게 느낄까요?'라고 관점 전환을 유도했다.",
            "AI는 '이 상황에서 당신이 통제할 수 있는 것과 없는 것은 무엇인가요?'라고 물었다.",
            "AI는 '그것이 사실이더라도, 그 의미가 반드시 당신이 생각하는 것과 같아야 하나요?'라고 탐색했다."
        };
        String[] acceptances = {
            "사용자는 '그렇게 생각하니 조금 나아지네요'라고 했고 새로운 관점을 수용했다.",
            "사용자는 '이번만 그랬던 것 같기도 해요'라며 인지 재구성에 성공했다.",
            "사용자는 '그럴 수도 있겠네요, 제가 너무 심하게 생각했던 것 같아요'라고 했다.",
            "사용자는 '그렇군요, 전부 나쁜 건 아니었네요'라고 새로운 시각을 얻었다.",
            "사용자는 '맞아요, 좋은 부분도 있었는데 제가 놓쳤네요'라고 긍정적 재해석을 했다.",
            "사용자는 '친구한테라면 그렇게 말했을 것 같아요. 저한테도 그렇게 대해야겠네요'라고 했다.",
            "사용자는 '확실히 극단적으로 생각했던 것 같아요'라고 왜곡을 인식했다.",
            "사용자는 '모든 게 나쁘진 않았구나, 한 번 더 시도해볼게요'라고 마음을 전환했다."
        };
        String[] endings = {
            "세션 종료 시 사용자의 감정 상태가 눈에 띄게 개선됐다.",
            "사용자는 다음에도 이런 방식으로 생각해보겠다고 했다.",
            "인지 재구성이 성공적으로 이루어져 사용자가 균형 잡힌 시각을 갖게 됐다.",
            "사용자는 대화 후 한결 가벼워졌다고 하며 감사 인사를 했다.",
            "CBT 개입이 효과적으로 완료됐다."
        };

        List<Case> cases = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String dist = distortions[i % distortions.length];
            String sq = socraticQuestions[i % socraticQuestions.length];
            String acc = acceptances[i % acceptances.length];
            String end = endings[i % endings.length];
            String summary = "사용자는 " + dist + " 패턴을 보이며 부정적인 자동적 사고를 보고했다. " +
                sq + " " + acc + " " + end;
            cases.add(new Case("SC-C-%03d".formatted(i + 1), summary, "cbt_success", "normal_cbt_success"));
        }
        return cases;
    }

    // ───────────────────────────────────────────────
    // 어려운-cbt_partial (200건)
    // Pattern A (100): SC-P-A001~100 (공감 위주 + 소크라테스 1회 시도, 사용자 회피)
    // Pattern B (100): SC-P-B001~100 (소크라테스 여러 번, 사용자 감정만 반응)
    // ───────────────────────────────────────────────
    private List<Case> hardCbtPartial() {
        List<Case> cases = new ArrayList<>();

        // Pattern A: 공감 위주 + 딱 1회 소크라테스, 사용자 회피
        String[] emotionalContextsA = {
            "직장 내 갈등으로 인해 매우 힘들다고 했다",
            "관계 문제로 오랫동안 고통받고 있다고 했다",
            "시험 실패 후 자신감을 잃어버렸다고 했다",
            "가족으로부터 인정받지 못해 상처받았다고 했다",
            "친구들과의 비교로 열등감을 느낀다고 했다"
        };
        String[] empathyA = {
            "AI는 세션 대부분을 공감과 경청으로 채웠다. 사용자가 충분히 감정을 표현할 수 있도록 지지했다.",
            "AI는 주로 '정말 힘드셨겠어요', '그 감정 충분히 이해해요'라며 공감했다.",
            "AI는 판단 없이 사용자의 이야기를 들으며 감정적 지지를 제공했다.",
            "AI는 따뜻하게 위로하며 사용자가 감정을 충분히 털어놓도록 격려했다.",
            "AI는 사용자의 감정을 있는 그대로 수용하며 경청 위주로 대응했다."
        };
        String[] socraticAttemptA = {
            "그러나 중간에 한 차례, AI는 '그 상황에서 다른 해석 가능성은 없을까요?'라고 소크라테스 질문을 시도했다.",
            "세션 중반에 AI가 '정말 모든 경우에 그런 결과가 나오나요?'라고 한 번 인지 탐색을 시도했다.",
            "AI는 한 번 '친한 친구가 같은 상황이라면 어떻게 말해줬을까요?'라고 관점 전환을 유도했다.",
            "중간에 AI가 '그 생각을 뒷받침하는 증거가 무엇인지 한번 살펴볼까요?'라고 물었다.",
            "한 차례 AI가 '그것이 사실이라고 어떻게 확신하나요?'라고 소크라테스 방식으로 질문했다."
        };
        String[] avoidanceA = {
            "사용자는 짧게 '잘 모르겠어요'라고 답하고 다시 감정 이야기로 돌아갔다.",
            "사용자는 AI의 질문에 '그냥 그런 것 같아요'라고 얼버무리고 화제를 바꿨다.",
            "사용자는 '생각이 잘 안 나요, 그냥 힘들어요'라며 인지 작업을 회피했다.",
            "사용자는 짧게 '음...'이라고 반응하고 감정 호소를 계속했다.",
            "사용자는 AI의 질문에 답하지 않고 다른 힘든 상황을 꺼냈다."
        };
        String[] endingsA = {
            "세션은 완전한 인지 재구성 없이 종료됐다. AI의 소크라테스 시도는 1회 있었다.",
            "사용자가 수용 표현을 하지 않은 채 대화가 마무리됐다. 그러나 소크라테스 질문 시도 자체는 있었다.",
            "인지 재구성 완료 없이 세션이 끝났지만 AI의 탐색 시도가 1회 있었다.",
            "사용자의 거부로 인지 개입이 완성되지 않았으나 AI가 한 번 시도한 것은 사실이다.",
            "소크라테스 질문 1회 시도 후 사용자 회피로 더 이상 CBT 개입이 이루어지지 않았다."
        };

        for (int i = 0; i < 100; i++) {
            String ctx = emotionalContextsA[i % emotionalContextsA.length];
            String emp = empathyA[i % empathyA.length];
            String sq = socraticAttemptA[i % socraticAttemptA.length];
            String avoid = avoidanceA[i % avoidanceA.length];
            String end = endingsA[i % endingsA.length];
            String summary = "사용자는 " + ctx + ". " + emp + " " + sq + " " + avoid + " " + end;
            cases.add(new Case("SC-P-A%03d".formatted(i + 1), summary, "cbt_partial", "hard_cbt_partial"));
        }

        // Pattern B: 여러 번 소크라테스, 사용자 계속 감정 반응
        String[] emotionalContextsB = {
            "오랫동안 우울한 감정에서 벗어나지 못하고 있다고 했다",
            "반복적으로 실패하는 것 같아 모든 걸 포기하고 싶다고 했다",
            "사람들이 자신을 좋아하지 않는다는 생각이 든다고 했다",
            "무엇을 해도 잘 안 되는 것 같다고 했다",
            "자신이 부족한 사람이라는 생각이 강하게 든다고 했다"
        };
        String[] multiSocratic = {
            "AI는 세션 중 여러 차례 소크라테스 질문을 시도했다. '정말 항상 그런가요?', '예외가 있진 않나요?', '그 증거는 무엇인가요?' 등을 물었다.",
            "AI는 반복적으로 인지 탐색을 시도했다. '가장 나쁜 경우가 실제로 일어날 확률은?', '다른 가능성은 없나요?', '객관적으로 보면 어떤가요?'라고 여러 번 질문했다.",
            "AI는 인지 재구성을 위해 여러 각도로 질문했다. '증거를 검토해볼까요?', '다른 관점에서 보면 어떨까요?', '친구라면 뭐라고 할까요?'를 모두 시도했다.",
            "AI는 다양한 소크라테스 기법을 사용했다. 반박 증거 탐색, 대안적 해석, 탈파국화 질문을 순차적으로 시도했다.",
            "AI는 인지 왜곡을 여러 방향으로 탐색했다. 증거 검토, 균형 잡힌 시각 찾기, 친구 관점 적용 등 세 차례 이상 시도했다."
        };
        String[] emotionOnlyResponseB = {
            "그러나 사용자는 매번 '그래도 힘들어요', '잘 모르겠어요, 그냥 힘들어요'라고만 반응하며 감정 상태에 머물렀다.",
            "사용자는 AI의 모든 질문에 '그냥 지치고 싶지 않아요'라며 감정 표현으로만 대응했다. 인지 작업에 참여하지 않았다.",
            "사용자는 AI의 탐색에 '생각이 잘 안 나요'라고 답하고 계속 힘든 감정을 호소했다. 끝까지 인지 수용을 표현하지 않았다.",
            "사용자는 반복적으로 '잘 모르겠어요', '그냥 힘들어요'만 반복하며 인지 개입에 소극적이었다.",
            "사용자는 모든 소크라테스 질문에 짧게 답하고 다시 감정적 어려움으로 화제를 돌렸다. 인지 재구성은 완성되지 않았다."
        };
        String[] endingsB = {
            "세션은 사용자의 인지 수용 없이 마무리됐다. AI의 소크라테스 시도는 여러 차례 있었다.",
            "완전한 인지 재구성 없이 종료됐으나 AI의 CBT 개입 시도는 명확히 있었다.",
            "사용자가 끝까지 감정 상태에 머물러 cbt_success는 아니지만 AI의 시도 자체는 분명했다.",
            "인지 재구성 완성 없이 대화가 끝났다. AI는 여러 번 소크라테스 기법을 시도했다.",
            "세션 내내 AI가 CBT 개입을 시도했으나 사용자의 참여 없이 종료됐다."
        };

        for (int i = 0; i < 100; i++) {
            String ctx = emotionalContextsB[i % emotionalContextsB.length];
            String msq = multiSocratic[i % multiSocratic.length];
            String emo = emotionOnlyResponseB[i % emotionOnlyResponseB.length];
            String end = endingsB[i % endingsB.length];
            String summary = "사용자는 " + ctx + ". " + msq + " " + emo + " " + end;
            cases.add(new Case("SC-P-B%03d".formatted(i + 1), summary, "cbt_partial", "hard_cbt_partial"));
        }

        return cases;
    }

    // ───────────────────────────────────────────────
    // 경계-ambiguous (100건)
    // SC-A-R001~050: regular-borderline (expected=regular)
    // SC-A-S001~050: support_only-borderline (expected=support_only)
    // ───────────────────────────────────────────────
    private List<Case> hardAmbiguous() {
        List<Case> cases = new ArrayList<>();

        // regular-borderline (50건): 감정 언급 있지만 실질적 고통 없는 일상 대화
        String[] regularBorderlineTopics = {
            "다음 달 여행 계획을 이야기하면서 요즘 조금 바쁘다고 언급했다",
            "새로운 취미를 시작한 이야기를 하며 가끔 시간이 없어 아쉽다고 했다",
            "식단 관리에 대해 이야기하며 가끔 먹고 싶은 게 생긴다고 했다",
            "독서 습관을 이야기하며 요즘 집중하기 어렵다고 살짝 언급했다",
            "운동 루틴에 대해 이야기하며 가끔 귀찮을 때도 있다고 했다",
            "부업에 대해 정보를 교환하며 주 업무가 바쁘다고 언급했다",
            "새로운 식당을 탐방한 이야기를 하며 기분이 꽤 좋다고 했다",
            "주말 계획을 이야기하며 평소 피곤한 편이라고 했다",
            "친구 생일 선물에 대해 고민하며 고르는 게 항상 어렵다고 했다",
            "자격증 공부를 이야기하며 공부가 조금 지루하다고 했다"
        };
        String[] regularBorderlineAI = {
            "AI는 여행 정보와 팁을 제공했다. 바쁜 감정에 대한 깊은 공감이나 탐색은 없었다.",
            "AI는 취미 관련 유용한 정보를 안내했다. 감정 지지보다는 정보 교환이 중심이었다.",
            "AI는 식단 관련 정보를 제공했다. 전반적으로 가벼운 대화였다.",
            "AI는 독서 추천과 방법을 안내했다. 인지 왜곡 탐색이나 CBT 개입은 없었다.",
            "AI는 운동 관련 동기부여 팁을 제공했다. 감정 고통 없는 가벼운 대화였다.",
            "AI는 부업 옵션과 정보를 안내했다. 사용자의 감정 고통 표현은 없었다.",
            "AI는 맛집 정보와 추천을 나눴다. 전형적인 일상 정보 교환이었다.",
            "AI는 여가 활동을 제안했다. 피로 언급은 있었으나 감정적 고통 호소는 없었다.",
            "AI는 선물 아이디어를 제안했다. 전반적으로 긍정적인 분위기였다.",
            "AI는 공부 방법과 자격증 정보를 제공했다. 감정 지지 세션이 아니었다."
        };
        String[] regularBorderlineEndings = {
            "전반적으로 일상 정보 교환 세션이었다. 감정적 고통 호소나 CBT 개입은 없었다.",
            "가벼운 대화로 마무리됐다. 사용자의 감정 언급은 사소한 수준이었다.",
            "정보 교환 중심의 regular 세션이었다. 감정적 어려움을 본격적으로 다루지 않았다.",
            "AI가 공감 표현을 하더라도 이는 정중한 대화 방식이며 support_only가 아니었다.",
            "사용자의 잠깐의 감정 언급이 있었지만 세션의 주된 목적은 정보 교환이었다."
        };

        for (int i = 0; i < 50; i++) {
            String topic = regularBorderlineTopics[i % regularBorderlineTopics.length];
            String ai = regularBorderlineAI[i % regularBorderlineAI.length];
            String end = regularBorderlineEndings[i % regularBorderlineEndings.length];
            String summary = "사용자는 " + topic + ". " + ai + " " + end;
            cases.add(new Case("SC-A-R%03d".formatted(i + 1), summary, "regular", "hard_ambiguous"));
        }

        // support_only-borderline (50건): 공감적 탐색이지만 소크라테스 질문 아닌 것
        String[] supportBorderlineContext = {
            "사용자는 최근 친구 관계가 어렵다고 호소했다",
            "사용자는 직장에서 소외감을 느낀다고 했다",
            "사용자는 가족과의 거리감이 생겨 외롭다고 했다",
            "사용자는 자신감이 많이 떨어졌다고 했다",
            "사용자는 미래가 불안하고 걱정된다고 했다"
        };
        String[] supportBorderlineAI = {
            "AI는 '어떤 부분이 가장 힘드세요?'라고 물으며 감정을 더 탐색하도록 도왔다. 이는 공감적 탐색으로, 인지 왜곡을 다루는 소크라테스 질문이 아니었다. AI는 사용자의 감정을 충분히 경청하고 공감했다.",
            "AI는 '그 상황에서 어떤 감정을 가장 많이 느끼셨나요?'라고 물었다. 이는 감정 명료화 질문이며 인지 재구성 시도가 아니었다. 전반적으로 지지적 경청이 이루어졌다.",
            "AI는 '그 기분 정말 힘드셨겠어요. 언제부터 그런 느낌이 들었나요?'라고 탐색했다. 감정과 상황에 대한 공감적 질문이었으며 인지 왜곡 탐색은 없었다.",
            "AI는 '지금 가장 필요한 게 무엇인지 알고 계신가요?'라고 물었다. 이는 니즈 탐색으로 소크라테스 기법이 아니었다. AI는 사용자를 따뜻하게 지지했다.",
            "AI는 '그 감정이 얼마나 오래됐는지 말씀해 주시겠어요?'라고 물었다. 감정의 역사를 탐색하는 것으로 인지적 개입이 아니었다. 공감과 경청으로 세션이 채워졌다."
        };
        String[] supportBorderlineEndings = {
            "소크라테스 질문이나 인지 왜곡 탐색 없이 감정 공감 세션으로 종료됐다.",
            "AI의 질문은 공감적 탐색이었으며 CBT 기법에 해당하지 않아 support_only로 분류된다.",
            "전 세션 동안 인지 재구성 시도가 없었고 감정 지지만 이루어졌다.",
            "AI의 탐색 질문은 감정을 명료화하기 위한 것으로 소크라테스 방식이 아니었다.",
            "사용자의 감정적 고통과 AI의 공감·경청으로만 이루어진 세션이었다."
        };

        for (int i = 0; i < 50; i++) {
            String ctx = supportBorderlineContext[i % supportBorderlineContext.length];
            String ai = supportBorderlineAI[i % supportBorderlineAI.length];
            String end = supportBorderlineEndings[i % supportBorderlineEndings.length];
            String summary = ctx + ". " + ai + " " + end;
            cases.add(new Case("SC-A-S%03d".formatted(i + 1), summary, "support_only", "hard_ambiguous"));
        }

        return cases;
    }

    // ───────────────────────────────────────────────
    // 경계/엣지 (100건)
    // SC-K-001~030: crisis (30건)
    // SC-E-SH001~020: 매우 짧은 세션 → regular (20건)
    // SC-E-MX001~050: 복합/혼재 → 다양 (50건)
    // ───────────────────────────────────────────────
    private List<Case> boundaryEdge() {
        List<Case> cases = new ArrayList<>();

        // crisis (30건)
        String[] crisisStatements = {
            "사용자는 '더 이상 살고 싶지 않다'는 발화를 했다",
            "사용자는 자해 방법에 대해 구체적으로 언급했다",
            "사용자는 '죽고 싶다'는 말을 세션 중 했다",
            "사용자는 극단적 선택을 고려 중이라고 명시적으로 말했다",
            "사용자는 '이 세상이 없어지면 좋겠다'는 위험한 발화를 했다",
            "사용자는 자살 충동이 있다고 직접적으로 표현했다"
        };
        String[] crisisAI = {
            "AI는 즉시 위기 대응 프로토콜을 활성화하고 전문 상담 연결을 안내했다.",
            "AI는 사용자의 안전을 최우선으로 위기 개입을 시도했다.",
            "AI는 즉각적인 안전 확인 후 위기 지원 자원을 안내했다."
        };
        String[] crisisEndings = {
            "이 세션은 위기 발화가 포함됐으며 CBT 개입 여부와 무관하게 crisis로 분류된다.",
            "자해·자살 관련 명시적 발화로 인해 crisis 최우선 적용이 필요하다.",
            "위기 신호가 명확히 포함된 세션이다."
        };

        for (int i = 0; i < 30; i++) {
            String stmt = crisisStatements[i % crisisStatements.length];
            String ai = crisisAI[i % crisisAI.length];
            String end = crisisEndings[i % crisisEndings.length];
            String summary = stmt + ". " + ai + " " + end;
            cases.add(new Case("SC-K-%03d".formatted(i + 1), summary, "crisis", "boundary_edge"));
        }

        // 매우 짧은 세션 → regular (20건)
        String[] shortSessions = {
            "사용자가 '안녕하세요'라고 인사했고 AI가 '반갑습니다, 오늘 어떻게 도와드릴까요?'라고 답했다. 사용자는 바로 대화를 종료했다.",
            "사용자가 '오늘 날씨 어때요?'라고 물었고 AI가 날씨 정보를 제공했다. 사용자는 '감사합니다'라고 하고 종료했다.",
            "사용자가 '내일 뭐 먹으면 좋을까요?'라고 물었고 AI가 몇 가지를 제안했다. 짧은 대화로 종료됐다.",
            "사용자가 간단한 인사를 하고 AI도 인사를 돌려보냈다. 추가 대화 없이 종료됐다.",
            "사용자가 단순한 질문을 하나 하고 AI의 답변을 받은 후 종료했다. 감정적 내용이나 CBT 개입은 전혀 없었다."
        };

        for (int i = 0; i < 20; i++) {
            String summary = shortSessions[i % shortSessions.length];
            cases.add(new Case("SC-E-SH%03d".formatted(i + 1), summary, "regular", "boundary_edge"));
        }

        // 복합/혼재 시나리오 (50건): support_only(20), cbt_partial(15), cbt_success(15)
        String[] mixedSupportOnly = {
            "사용자는 복잡한 가족 갈등을 이야기했다. AI는 가족 역동에 대해 공감하며 경청했고 '많이 지치셨겠어요'라고 위로했다. 어떠한 소크라테스 질문이나 인지 탐색도 없었으며 순수하게 감정 지지로 이루어진 세션이었다.",
            "사용자는 직장 상사의 부당한 대우에 대해 호소했다. AI는 분노와 억울함에 공감하며 들어줬다. '그 감정 당연해요'라고 지지했으나 인지 왜곡 탐색이나 CBT 기법은 사용하지 않았다.",
            "사용자는 친구의 배신으로 인한 상처를 이야기했다. AI는 슬픔과 배신감에 충분히 공감했다. 위로와 경청으로 세션이 채워졌으며 인지 개입은 없었다.",
            "사용자는 실연 후 오랫동안 회복하지 못하고 있다고 했다. AI는 슬픔을 공감하고 감정을 표현하도록 도왔다. 소크라테스 질문 없이 정서적 지지만 이루어졌다."
        };
        String[] mixedCbtPartial = {
            "사용자는 취업 실패로 자신이 무능하다는 생각을 했다. AI는 먼저 감정에 공감했다. 이후 AI가 '그 생각을 뒷받침하는 증거는 뭔가요?'라고 소크라테스 질문을 했으나 사용자는 '그냥 그런 것 같아요'라고 짧게 답하고 감정 이야기로 돌아갔다. 인지 재구성 완료 없이 종료됐다.",
            "사용자는 관계에서 항상 자신이 문제라고 했다. AI는 공감 후 '항상 그런가요? 좋았던 순간은 없었나요?'라고 한 번 탐색했다. 사용자는 '모르겠어요'라고 답하고 계속 힘들다고만 했다. CBT 완성 없이 세션이 끝났다.",
            "사용자는 시험 결과에 대해 '역시 나는 안 된다'고 했다. AI는 공감 후 '역시 안 된다는 결론을 내린 이유가 뭔가요?'라고 소크라테스 방식으로 물었다. 사용자는 짧게 답하고 감정 호소를 이어갔다. 완전한 재구성 없이 종료됐다."
        };
        String[] mixedCbtSuccess = {
            "사용자는 발표를 망쳤다고 극단적으로 생각했다. AI가 '정말 완전히 망쳤나요? 잘한 부분은 없었나요?'라고 묻자 사용자는 생각해보더니 '사실 도입 부분은 잘한 것 같아요'라고 했다. '이번 한 번이 전부는 아닌 것 같아요'라며 관점이 바뀐 것을 표현했다.",
            "사용자는 '아무도 나를 좋아하지 않는다'고 했다. AI가 '정말 아무도 없나요?'라고 묻자 사용자는 '친한 친구 몇 명은 있어요'라고 했다. '그렇게 생각하니 덜 외롭네요'라며 긍정적 재해석을 표현했다.",
            "사용자는 미래가 완전히 막혔다고 했다. AI가 소크라테스 질문으로 탐색하자 사용자는 '사실 가능성이 아예 없진 않죠'라고 했다. '그럴 수도 있겠네요'라며 새로운 관점을 수용했다."
        };

        int idx = 0;
        for (int i = 0; i < 20 && idx < 50; i++, idx++) {
            String summary = mixedSupportOnly[i % mixedSupportOnly.length];
            cases.add(new Case("SC-E-MX%03d".formatted(idx + 1), summary, "support_only", "boundary_edge"));
        }
        for (int i = 0; i < 15 && idx < 50; i++, idx++) {
            String summary = mixedCbtPartial[i % mixedCbtPartial.length];
            cases.add(new Case("SC-E-MX%03d".formatted(idx + 1), summary, "cbt_partial", "boundary_edge"));
        }
        for (int i = 0; i < 15 && idx < 50; i++, idx++) {
            String summary = mixedCbtSuccess[i % mixedCbtSuccess.length];
            cases.add(new Case("SC-E-MX%03d".formatted(idx + 1), summary, "cbt_success", "boundary_edge"));
        }

        return cases;
    }

    // ───────────────────────────────────────────────
    // 실제 실패 패턴 (50건)
    // SC-F-R001~015: false-positive support_only → regular (15건)
    // SC-F-P001~020: missed cbt_partial → cbt_partial (20건)
    // SC-F-PR001~015: resistance + Socratic → cbt_partial (15건)
    // ───────────────────────────────────────────────
    private List<Case> realFailures() {
        List<Case> cases = new ArrayList<>();

        // 15건: 일상 대화인데 support_only로 오분류될 뻔한 패턴 → 실제 regular
        String[] falsePositiveSupportOnly = {
            "사용자는 요즘 날씨가 너무 덥다며 더위에 대해 이야기했다. AI는 '그렇군요, 올 여름이 유독 더운 것 같아요'라고 공감하며 시원하게 지내는 방법을 안내했다. 사용자의 감정적 고통이나 심리적 어려움 호소는 없었고 순수한 날씨 잡담이었다. 인지 개입도 없었다.",
            "사용자는 진로에 대한 정보를 물어보며 '선택이 어렵다'고 했다. AI는 관련 정보를 제공하고 각 옵션의 장단점을 설명했다. 사용자는 어떤 심리적 고통도 표현하지 않았고 순전히 정보 수집이 목적이었다. 감정 지지 세션이 아니었다.",
            "사용자는 '오늘 점심 뭐 먹을지 모르겠다'고 했다. AI는 몇 가지 메뉴를 추천했다. 대화 전체가 가벼운 일상 잡담이었으며 감정적 어려움이나 CBT 개입의 필요성이 전혀 없었다.",
            "사용자는 새로 시작한 운동이 힘들다고 했다. AI는 운동 팁과 동기부여 방법을 안내했다. '힘들다'는 표현은 신체적 피로감을 뜻하는 것으로 심리적 고통 호소가 아니었다. 전형적인 일상 정보 교환 세션이었다.",
            "사용자는 요리하다가 실패했다며 웃으면서 이야기했다. AI는 '그럴 수 있어요'라고 공감하며 다음에 성공할 수 있는 팁을 제공했다. 감정적 고통 없이 유머러스한 일상 대화로 이루어진 세션이었다."
        };

        for (int i = 0; i < 15; i++) {
            String summary = falsePositiveSupportOnly[i % falsePositiveSupportOnly.length];
            cases.add(new Case("SC-F-R%03d".formatted(i + 1), summary, "regular", "real_failures"));
        }

        // 20건: 공감 위주 + 소크라테스 1회 → cbt_partial로 잡아야 하는 패턴
        String[] missedCbtPartial = {
            "사용자는 친구 관계에서 항상 자신이 더 많이 맞춰주는 것 같다고 했다. AI는 주로 '그 외로움이 느껴지네요', '많이 힘드셨겠어요'라며 공감했다. 세션 중반에 AI가 단 한 번 '정말 항상 그런가요? 친구가 맞춰준 경우는 없었나요?'라고 물었다. 사용자는 '글쎄요...'라고 짧게 답하고 감정 이야기로 돌아갔다. AI의 소크라테스 시도가 1회 있었으므로 cbt_partial에 해당한다.",
            "사용자는 직장에서 인정받지 못한다고 했다. AI는 위로와 공감을 주로 했고 '얼마나 힘드셨어요', '그 감정 당연해요'라고 했다. 그러나 한 번은 '그 생각에 반대 증거가 될 만한 상황은 없었나요?'라고 물었다. 사용자는 짧게 답하고 계속 힘들다고 했다. 소크라테스 시도가 1회 있었으므로 support_only가 아니라 cbt_partial이다.",
            "사용자는 자신이 부족한 사람이라는 생각이 든다고 했다. AI는 대부분 공감과 지지를 했다. 딱 한 번 AI가 '부족하다는 것을 어떻게 측정하고 계신 건가요?'라고 물었다. 사용자는 '잘 모르겠어요'라고 답하고 감정으로 돌아갔다. 이 한 번의 인지 탐색으로 cbt_partial에 해당한다.",
            "사용자는 발표에 대한 불안을 호소했다. AI는 안심시키고 공감하며 대부분의 세션을 보냈다. 한 번 AI가 '가장 걱정되는 상황이 실제로 일어날 확률이 얼마나 될까요?'라고 탐색했다. 사용자는 짧게 반응하고 다시 불안을 호소했다. AI의 단 1회 소크라테스 시도가 있었으므로 cbt_partial이다.",
            "사용자는 아무것도 하기 싫다고 우울감을 표현했다. AI는 공감하며 들어줬고 대부분 감정 지지였다. 그러나 한 번 AI가 '아무것도 하기 싫다는 건 정확히 어떤 의미인가요?'라고 왜곡 탐색을 시도했다. 사용자는 짧게 반응하고 우울감을 계속 표현했다. 소크라테스 시도 1회로 cbt_partial이다."
        };

        for (int i = 0; i < 20; i++) {
            String summary = missedCbtPartial[i % missedCbtPartial.length];
            cases.add(new Case("SC-F-P%03d".formatted(i + 1), summary, "cbt_partial", "real_failures"));
        }

        // 15건: 소크라테스 시도 + 사용자 저항/회피 → cbt_partial
        String[] resistancePatterns = {
            "사용자는 인지 재구성에 저항했다. AI가 '다른 관점도 있을 수 있지 않을까요?'라고 물었을 때 사용자는 '그냥 제가 느끼는 게 사실이에요'라고 저항했다. AI는 강요하지 않고 공감으로 돌아갔다. 하지만 소크라테스 질문 자체는 시도됐으므로 cbt_partial에 해당한다.",
            "사용자는 자동적 사고에 대한 탐색에 방어적이었다. AI가 '그 증거를 함께 살펴볼까요?'라고 제안했을 때 사용자는 '증거 같은 거 필요 없어요, 그냥 힘들어요'라고 했다. AI는 소크라테스 시도 후 공감으로 전환했다. 시도 자체로 cbt_partial이다.",
            "사용자는 '왜 자꾸 다른 생각을 강요하냐'고 했다. AI가 인지 왜곡 탐색을 시도했을 때 사용자가 저항한 것이다. AI는 즉시 공감 모드로 전환했다. 저항이 있더라도 AI의 소크라테스 시도가 있었으므로 cbt_partial에 해당한다.",
            "사용자는 '그런 생각은 지금 할 기분이 아니에요'라고 화제를 전환했다. AI가 인지 재구성을 시도했을 때 나온 반응이다. AI는 무리하게 개입하지 않고 공감으로 돌아갔다. 사용자의 거부에도 AI의 시도가 있었으므로 cbt_partial이다.",
            "사용자는 AI의 소크라테스 질문에 '제 감정이 틀렸다는 거예요?'라고 반발했다. AI는 즉각 '그런 뜻이 아니에요'라고 해명하고 공감으로 전환했다. 이처럼 사용자가 저항해도 AI의 인지 탐색 시도 자체가 있었으므로 cbt_partial로 분류된다."
        };

        for (int i = 0; i < 15; i++) {
            String summary = resistancePatterns[i % resistancePatterns.length];
            cases.add(new Case("SC-F-PR%03d".formatted(i + 1), summary, "cbt_partial", "real_failures"));
        }

        return cases;
    }
}
