package com.mio.ai.qa;

import com.mio.ai.llm.LlmPricingProperties.ModelPrice;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * 실행 직전에 모델 후보와 단가를 핀하는 registry (로드맵 §10.3·§11.3).
 *
 * <p>로드맵은 두 가지를 명시했다. 하나, 모델은 <b>역할별 registry</b> 로 관리한다. 둘,
 * <b>정확한 후보 ID 와 당시 단가는 코드 상수로 고정하지 않고 각 벤치마크 실행의 registry 에
 * 핀한다.</b> 그래서 상위 모델 ID 는 이 저장소 어디에도 적혀 있지 않고, 실행하는 사람이
 * 시스템 프로퍼티나 registry 파일로 그때의 값을 넣는다.
 *
 * <h2>핀하는 방법</h2>
 *
 * <pre>{@code
 * # 1) 시스템 프로퍼티 (Gradle: -PcellModels="generation=<id>,escalation=<id>")
 * -Dmio.eval.model.generation=<후보 모델 ID>
 * -Dmio.eval.price.<후보 모델 ID>=<input>/<cachedInput>/<output>   # 100만 토큰당 USD
 * -Dmio.eval.pricingAsOf=2026-08-16
 *
 * # 2) registry 파일 (커밋하지 않는다 — 후보 ID·단가는 실행 시점의 사실이다)
 * -Dmio.eval.registry=build/eval/model-registry.properties
 * }</pre>
 *
 * <h2>왜 fail-closed 인가</h2>
 *
 * <p>상위 모델 역할에 기본값을 하나 넣어 두면, 아무도 핀하지 않은 실행이 그럴듯한 숫자를
 * 남긴다. 그리고 그 숫자는 "어느 모델로 쟀는지 아무도 확인하지 않은 값" 이다. 셀 비교의
 * 전제가 "같은 split 을 알려진 모델 조합으로 돌렸다" 이므로, 전제를 못 채우면 실행하지
 * 않는 편이 낫다. {@link #resolve(BenchmarkCell)} 는 그래서 예외로 멈추고, 메시지에 핀하는
 * 방법을 그대로 적는다.
 */
final class CellModelRegistry {

    /** 역할별 모델 ID 를 지정하는 프로퍼티 접두사. */
    static final String MODEL_PROPERTY_PREFIX = "mio.eval.model.";
    /** 모델별 단가를 지정하는 프로퍼티 접두사. 값 형식은 {@code input/cachedInput/output}. */
    static final String PRICE_PROPERTY_PREFIX = "mio.eval.price.";
    static final String REGISTRY_FILE_PROPERTY = "mio.eval.registry";
    static final String PRICING_AS_OF_PROPERTY = "mio.eval.pricingAsOf";
    static final String SEED_PROPERTY = "mio.eval.seed";

    /**
     * 모델별 생성 출력 토큰 예산을 덮어쓰는 프로퍼티 접두사
     * ({@code -PcellMaxCompletionTokens="<모델 ID>=<정수>"}).
     *
     * <p>1단계 실 실행(run_id 826444f8)이 만든 문제를 푼다. 프로덕션 예산 400 토큰을 추론
     * 모델은 내부 추론에 전부 쓰고 사용자에게 보일 본문을 내지 못했다 — gpt-5-nano 는 47/47,
     * gpt-5-mini 는 43/47 턴에서 아무것도 전달하지 못했다. 그 상태로 잰 수치는 모델의 품질이
     * 아니라 "이 예산 안에서 말을 할 수 있는가" 이고, 두 모델을 비교했다고 말할 수 없다.
     *
     * <p><b>기본값은 프로덕션 상수 그대로다.</b> 덮어쓰지 않은 모델은 프로덕션과 같은 예산으로
     * 돌아 셀 원가가 프로덕션 원가를 잰다. 덮어쓴 모델은
     * {@link #raisedCompletionBudgets()} 에 남아 리포트·manifest 가 "이 수치는 프로덕션 예산으로
     * 잰 것이 아니다" 를 밝힌다.
     */
    static final String MAX_COMPLETION_TOKENS_PROPERTY_PREFIX = "mio.eval.maxCompletionTokens.";

    /**
     * 표본 추출 시드의 기본값.
     *
     * <p>실행마다 바뀌는 시드는 파일럿과 본 실행의 표본이 달라 비교를 무의미하게 만든다.
     * 기본값을 고정하고 그 값을 manifest 에 싣는다 — 바꾸고 싶으면 명시적으로 바꾸고,
     * 바꾼 사실이 기록에 남는다.
     */
    static final long DEFAULT_SEED = 454L;

    /**
     * 프로덕션 상수와 같아야 하는 운영 모델 기본값.
     *
     * <p>{@code ConversationOrchestrator.LLM_MODEL} / {@code InputJudge.JUDGE_MODEL} /
     * {@code OutputJudge.JUDGE_MODEL} 이 전부 {@code private static final} 이라 읽을 수 없다.
     * 그래서 여기 한 번 적고, 값이 어긋나면 {@link CellModelRegistryTest} 가 소스에서 상수를
     * 다시 읽어 대조한다 — 프로덕션이 모델을 바꾸면 기본값이 조용히 옛 값으로 남지 않는다.
     */
    private static final Map<CellModelRole, String> OPERATIONAL_DEFAULTS = Map.of(
            CellModelRole.GENERATION, "gpt-4o",
            CellModelRole.INPUT_SAFETY, "gpt-4o-mini",
            CellModelRole.OUTPUT_JUDGE, "gpt-4o-mini",
            CellModelRole.CBT_CLASSIFIER, "gpt-4o-mini");

    /** 경량 등급의 기본값. 상위 모델과 달리 기본값을 두는 이유는 프로덕션에 이미 있는 모델이기 때문이다. */
    private static final Map<CellModelRole, String> LIGHTWEIGHT_DEFAULTS = Map.of(
            CellModelRole.GENERATION, "gpt-4o-mini");

    private final Map<CellModelRole, String> models;
    private final Map<CellModelRole, String> sources;
    private final CellPricingBook pricing;
    private final long seed;
    private final Map<String, Integer> completionBudgets;

    private CellModelRegistry(Map<CellModelRole, String> models, Map<CellModelRole, String> sources,
                              CellPricingBook pricing, long seed,
                              Map<String, Integer> completionBudgets) {
        this.models = Map.copyOf(models);
        this.sources = Map.copyOf(sources);
        this.pricing = pricing;
        this.seed = seed;
        this.completionBudgets = Map.copyOf(completionBudgets);
    }

    /**
     * 셀이 요구하는 역할을 전부 채운 registry.
     *
     * @throws IllegalStateException 상위 모델 후보가 핀되지 않았을 때
     */
    static CellModelRegistry resolve(BenchmarkCell cell) {
        return resolve(cell, readPins());
    }

    /**
     * 핀되지 않은 상위 모델 후보의 자리 표시자.
     *
     * <p>비용 <b>추정</b>은 후보가 정해지기 전에도 돌아야 한다 — 견적을 보고 후보를 고르는
     * 순서가 자연스럽기 때문이다. 그래서 추정 경로에서만 이 값을 채우고, 단가표에 없는 이름이라
     * 그 셀의 원가는 자동으로 "미상" 이 된다. 실행 경로는 여전히 fail-closed 다.
     */
    static final String UNPINNED_PLACEHOLDER = "<미핀 상위 모델 후보>";

    /** 견적 전용 해석. 핀되지 않은 상위 모델을 자리 표시자로 채워 토큰 볼륨만 재게 한다. */
    static CellModelRegistry resolveForEstimate(BenchmarkCell cell, Map<String, String> pins) {
        Map<String, String> filled = new LinkedHashMap<>(pins);
        for (CellModelRole role : cell.frontierRoles()) {
            filled.putIfAbsent(MODEL_PROPERTY_PREFIX + role.key(), UNPINNED_PLACEHOLDER);
        }
        return resolve(cell, filled);
    }

    /**
     * 후보 스크리닝용 해석 — 이 셀의 <b>상위 모델 역할 전부</b>를 지정한 후보로 채운다.
     *
     * <p>후보가 {@code -PcellModels} 의 상위 모델 핀을 이긴다. 스크리닝의 정의가 "같은 자리에
     * 후보를 하나씩 갈아 끼워 본다" 이므로, 후보를 지정했는데 다른 핀이 남아 있으면 그 실행은
     * 이름과 다른 것을 잰다. 상위 모델이 아닌 역할(운영·경량)은 건드리지 않는다 — 그것까지
     * 바뀌면 후보 간 차이가 아니라 셀 정의가 바뀐다.
     *
     * @param variant 후보가 {@code null} 이면 기존 해석과 동일하다
     */
    static CellModelRegistry resolveForVariant(CellVariant variant, Map<String, String> pins) {
        if (variant.frontierCandidate() == null) {
            return resolve(variant.cell(), pins);
        }
        Map<String, String> overridden = new LinkedHashMap<>(pins);
        for (CellModelRole role : variant.cell().frontierRoles()) {
            overridden.put(MODEL_PROPERTY_PREFIX + role.key(), variant.frontierCandidate());
        }
        return resolve(variant.cell(), overridden);
    }

    /** 시스템 프로퍼티를 읽는 실행 경로용 후보 해석. */
    static CellModelRegistry resolveForVariant(CellVariant variant) {
        return resolveForVariant(variant, readPins());
    }

    /** 테스트가 핀 소스를 직접 넣을 수 있는 형태. 프로퍼티 읽기와 해석을 분리한다. */
    static CellModelRegistry resolve(BenchmarkCell cell, Map<String, String> pins) {
        Map<CellModelRole, String> models = new LinkedHashMap<>();
        Map<CellModelRole, String> sources = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        for (CellModelRole role : cell.requiredRoles()) {
            BenchmarkCell.ModelTier tier = cell.roles().get(role);
            String pinned = pins.get(MODEL_PROPERTY_PREFIX + role.key());
            if (pinned != null && !pinned.isBlank()) {
                models.put(role, pinned.trim());
                sources.put(role, "실행 직전 핀 (%s%s)".formatted(MODEL_PROPERTY_PREFIX, role.key()));
                continue;
            }
            String fallback = switch (tier) {
                case OPERATIONAL -> OPERATIONAL_DEFAULTS.get(role);
                case LIGHTWEIGHT -> LIGHTWEIGHT_DEFAULTS.get(role);
                case FRONTIER -> null;
            };
            if (fallback == null) {
                missing.add("%s (%s 등급)".formatted(role.key(), tier));
                continue;
            }
            models.put(role, fallback);
            sources.put(role, "프로덕션 기본값");
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(pinningInstruction(cell, missing));
        }

        return new CellModelRegistry(models, sources,
                CellPricingBook.load(readPrices(pins), pins.get(PRICING_AS_OF_PROPERTY)),
                readSeed(pins), readCompletionBudgets(pins));
    }

    /**
     * 못 채운 역할을 어떻게 채우는지까지 적은 실패 메시지.
     *
     * <p>"모델이 없다" 만 적으면 실행하는 사람이 소스를 읽어야 한다. 실패 지점에서 다음
     * 동작을 그대로 알려주는 편이, 급한 사람이 코드에 기본값을 박아 넣는 것을 막는다.
     */
    private static String pinningInstruction(BenchmarkCell cell, List<String> missing) {
        String flags = missing.stream()
                .map(m -> m.substring(0, m.indexOf(' ')))
                .map(role -> "  -D%s%s=<모델 ID>".formatted(MODEL_PROPERTY_PREFIX, role))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        return """
                셀 %s 를 실행할 수 없다 — 상위 모델 후보가 핀되지 않았다: %s

                로드맵 §11.3 은 "실제 후보 ID 와 당시 단가는 실행 직전에 registry 에 핀한다" 고 정했다.
                기본값을 두지 않는 이유는, 아무도 핀하지 않은 실행이 남긴 숫자는 어느 모델의 수치인지
                확인할 방법이 없기 때문이다.

                다음 중 하나로 핀한다.
                %s
                  -D%s<모델 ID>=<input>/<cachedInput>/<output>   (100만 토큰당 USD, 선택)
                  -D%s=<YYYY-MM-DD>                              (단가 기준일, 선택)

                또는 registry 파일: -D%s=<경로>
                Gradle: ./gradlew test -PllmTests -Pcells=%s -PcellModels="generation=<모델 ID>"
                """.formatted(cell.name(), String.join(", ", missing), flags,
                PRICE_PROPERTY_PREFIX, PRICING_AS_OF_PROPERTY, REGISTRY_FILE_PROPERTY, cell.name());
    }

    // ── 핀 소스 ─────────────────────────────────────────────────────

    /** 시스템 프로퍼티 + registry 파일. 프로퍼티가 파일을 이긴다. */
    private static Map<String, String> readPins() {
        Map<String, String> pins = new LinkedHashMap<>();
        String file = System.getProperty(REGISTRY_FILE_PROPERTY);
        if (file != null && !file.isBlank()) {
            pins.putAll(readRegistryFile(Path.of(file)));
        }
        System.getProperties().stringPropertyNames().stream()
                .filter(CellModelRegistry::isPinKey)
                .forEach(key -> pins.put(key, System.getProperty(key)));
        return pins;
    }

    private static boolean isPinKey(String key) {
        return key.startsWith(MODEL_PROPERTY_PREFIX)
                || key.startsWith(PRICE_PROPERTY_PREFIX)
                || key.startsWith(MAX_COMPLETION_TOKENS_PROPERTY_PREFIX)
                || key.equals(PRICING_AS_OF_PROPERTY)
                || key.equals(SEED_PROPERTY);
    }

    /**
     * {@code mio.eval.maxCompletionTokens.<model>=<정수>} 를 파싱한다.
     *
     * <p>프로덕션 예산보다 <b>낮은</b> 값도 받는다 — "예산을 줄이면 어떻게 되는가" 도 물어볼 수
     * 있는 질문이다. 다만 0 이하는 받지 않는다: 그건 예산이 아니라 생성 금지다.
     */
    private static Map<String, Integer> readCompletionBudgets(Map<String, String> pins) {
        Map<String, Integer> budgets = new LinkedHashMap<>();
        pins.forEach((key, value) -> {
            if (!key.startsWith(MAX_COMPLETION_TOKENS_PROPERTY_PREFIX)) {
                return;
            }
            String model = key.substring(MAX_COMPLETION_TOKENS_PROPERTY_PREFIX.length());
            int budget;
            try {
                budget = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "completion 토큰 예산이 정수가 아니다: %s=%s".formatted(key, value), e);
            }
            if (budget <= 0) {
                throw new IllegalArgumentException(
                        "completion 토큰 예산은 양수여야 한다: %s=%s — 0 은 예산이 아니라 생성 금지다"
                                .formatted(key, value));
            }
            budgets.put(model, budget);
        });
        return budgets;
    }

    private static Map<String, String> readRegistryFile(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(
                    "registry 파일을 찾지 못했다: " + path.toAbsolutePath()
                            + " — 경로를 잘못 적은 실행이 기본값으로 조용히 진행되면 안 된다");
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("registry 파일을 읽지 못했다: " + path, e);
        }
        Map<String, String> pins = new LinkedHashMap<>();
        properties.stringPropertyNames().forEach(key -> pins.put(key, properties.getProperty(key)));
        return pins;
    }

    /** {@code mio.eval.price.<model>=input/cachedInput/output} 를 파싱한다. */
    private static Map<String, ModelPrice> readPrices(Map<String, String> pins) {
        Map<String, ModelPrice> prices = new LinkedHashMap<>();
        pins.forEach((key, value) -> {
            if (!key.startsWith(PRICE_PROPERTY_PREFIX)) {
                return;
            }
            String model = key.substring(PRICE_PROPERTY_PREFIX.length());
            String[] parts = value.split("/", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException(
                        "단가 형식이 잘못됐다: %s=%s — <input>/<cachedInput>/<output> 형식이어야 한다 "
                                .formatted(key, value)
                                + "(캐시 단가가 공표되지 않은 모델은 '-' 를 적는다: 5.0/-/20.0)");
            }
            prices.put(model, new ModelPrice(
                    new BigDecimal(parts[0].trim()),
                    unpublishedOrValue(parts[1]),
                    new BigDecimal(parts[2].trim())));
        });
        return prices;
    }

    /**
     * 캐시 입력 단가가 <b>공표되지 않은</b> 모델의 표기.
     *
     * <p>일부 상위 모델({@code *-pro} 계열)은 캐시 입력 단가를 공개하지 않는다. 그것을 0 으로
     * 적으면 캐시된 프롬프트가 공짜로 집계되고, input 과 같게 적으면 "확인했더니 같더라" 와
     * 구별되지 않는다. {@code null} 로 두면 {@link CellPricingBook} 이 input 단가로 되돌아가
     * <b>보수적으로</b> 계산하며, 그 사실이 값 자체에 남는다.
     *
     * <p>지금 하네스는 캐시 토큰을 0 으로 넘기므로 실제 계산에는 영향이 없다. 나중에 캐시를
     * 쓰게 됐을 때 이 자리가 조용히 0 이 되지 않게 하는 것이 목적이다.
     */
    private static BigDecimal unpublishedOrValue(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "-".equals(trimmed) || "?".equals(trimmed)
                || "미상".equals(trimmed)) {
            return null;
        }
        return new BigDecimal(trimmed);
    }

    private static long readSeed(Map<String, String> pins) {
        String raw = pins.get(SEED_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SEED;
        }
        return Long.parseLong(raw.trim());
    }

    /** {@code -PcellModels="generation=x,escalation=y"} 형태를 프로퍼티 키로 편다. */
    static Map<String, String> parseInlinePins(String csv) {
        Map<String, String> pins = new LinkedHashMap<>();
        if (csv == null || csv.isBlank()) {
            return pins;
        }
        for (String entry : csv.split(",")) {
            String[] kv = entry.split("=", 2);
            if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) {
                throw new IllegalArgumentException(
                        "cellModels 항목 형식이 잘못됐다: '%s' — <역할>=<모델 ID> 여야 한다".formatted(entry));
            }
            pins.put(MODEL_PROPERTY_PREFIX + kv[0].trim().toLowerCase(Locale.ROOT), kv[1].trim());
        }
        return pins;
    }

    // ── 조회 ────────────────────────────────────────────────────────

    String modelFor(CellModelRole role) {
        String model = models.get(role);
        if (model == null) {
            throw new IllegalStateException(
                    "이 셀은 역할 '%s' 를 쓰지 않는다 — 셀 정의에 없는 역할을 호출하면 셀이 달라진다"
                            .formatted(role.key()));
        }
        return model;
    }

    boolean has(CellModelRole role) {
        return models.containsKey(role);
    }

    /** 컴포넌트 태그 → 모델 ID. {@link RoleModelRewritingLlmClient} 가 그대로 쓴다. */
    Map<String, String> componentToModel() {
        Map<String, String> mapping = new LinkedHashMap<>();
        models.forEach((role, model) -> {
            if (role.isOnline()) {
                mapping.put(role.component(), model);
            }
        });
        return mapping;
    }

    /** 실행 manifest 의 {@code models} 값. 역할 이름은 §10.3 어휘를 그대로 쓴다. */
    Map<String, String> manifestModels() {
        Map<String, String> out = new TreeMap<>();
        models.forEach((role, model) -> out.put(role.key(), model));
        return out;
    }

    /** 각 역할의 모델이 어디서 왔는지. 리포트가 "기본값이었는지 핀이었는지" 를 숨기지 않게 한다. */
    Map<String, String> pinSources() {
        Map<String, String> out = new TreeMap<>();
        sources.forEach((role, source) -> out.put(role.key(), source));
        return out;
    }

    CellPricingBook pricing() {
        return pricing;
    }

    /**
     * 이 모델의 생성 출력 토큰 예산.
     *
     * <p>덮어쓰지 않았으면 프로덕션 상수 그대로다 — 기본값이 프로덕션과 다르면 셀 원가가
     * 프로덕션 원가를 재지 않는다.
     */
    int maxCompletionTokensFor(String model) {
        return completionBudgets.getOrDefault(model, CellRunner.PRODUCTION_MAX_COMPLETION_TOKENS);
    }

    /**
     * 프로덕션 예산과 <b>다른</b> 값으로 잰 모델.
     *
     * <p>비어 있지 않으면 그 실행의 원가·지연은 프로덕션 수치가 아니다. 리포트와 manifest 가
     * 그 사실을 그대로 찍는다 — 빈칸으로 두면 나중에 "프로덕션 예산으로 잰 값" 과 구별되지
     * 않는다.
     */
    Map<String, Integer> raisedCompletionBudgets() {
        Map<String, Integer> raised = new TreeMap<>();
        completionBudgets.forEach((model, budget) -> {
            if (budget != CellRunner.PRODUCTION_MAX_COMPLETION_TOKENS) {
                raised.put(model, budget);
            }
        });
        return raised;
    }

    /** 이 실행에서 <b>실제로 쓰이는</b> 역할별 예산. manifest 에 그대로 실린다. */
    Map<String, String> completionBudgetSummary() {
        Map<String, String> summary = new TreeMap<>();
        models.forEach((role, model) -> {
            int budget = maxCompletionTokensFor(model);
            summary.put(model, budget == CellRunner.PRODUCTION_MAX_COMPLETION_TOKENS
                    ? "%d (프로덕션 예산)".formatted(budget)
                    : "%d (프로덕션 예산 %d 에서 덮어씀)"
                            .formatted(budget, CellRunner.PRODUCTION_MAX_COMPLETION_TOKENS));
        });
        return summary;
    }

    long seed() {
        return seed;
    }

    /** 단가가 등록되지 않은 온라인 모델. 있으면 그 셀의 원가는 "미상" 이 된다. */
    List<String> unpricedOnlineModels() {
        return models.entrySet().stream()
                .filter(e -> e.getKey().isOnline())
                .map(Map.Entry::getValue)
                .distinct()
                .filter(model -> !pricing.isPriced(model))
                .sorted()
                .toList();
    }
}
