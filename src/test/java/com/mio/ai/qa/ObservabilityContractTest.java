package com.mio.ai.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityContractTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"));
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Yaml YAML = new Yaml();

    @Test
    @DisplayName("관측성 스택은 명시적 profile에서만 기동하고 관리 포트를 외부에 열지 않는다")
    void observabilityStackIsOptInAndPrivate() throws IOException {
        Map<String, Object> compose = yaml("docker-compose.observability.yml");
        Map<String, Object> services = map(compose.get("services"));

        assertThat(services).containsOnlyKeys("prometheus", "alertmanager", "grafana");
        for (String name : services.keySet()) {
            Map<String, Object> service = map(services.get(name));
            assertThat(list(service.get("profiles")))
                    .as(name + "은 기본 production compose와 함께 자동 기동되면 안 된다")
                    .contains("observability");
            assertThat(list(service.get("ports")))
                    .allMatch(port -> port.toString().startsWith("127.0.0.1:"));
        }

        String composeText = text("docker-compose.observability.yml");
        assertThat(composeText).doesNotContain("9090:9090");
        assertThat(composeText)
                .contains("alertmanager_slack_webhook")
                .contains("GF_PLUGINS_PREINSTALL_DISABLED: \"true\"");
        assertThat(ROOT.resolve("ops/observability/grafana/provisioning/alerting")).isDirectory();
        assertThat(ROOT.resolve("ops/observability/grafana/provisioning/plugins")).isDirectory();
        assertThat(ROOT.resolve("ops/observability/grafana/provisioning/alerting/.gitkeep"))
                .as("Grafana는 provisioning 디렉터리의 알 수 없는 확장자를 경고한다")
                .doesNotExist();
    }

    @Test
    @DisplayName("Prometheus는 앱 관리망을 scrape하고 규칙을 Alertmanager로 전달한다")
    void prometheusScrapesPrivateManagementPortAndRoutesAlerts() throws IOException {
        Map<String, Object> prometheus = yaml("ops/observability/prometheus/prometheus.yml");

        assertThat(text("ops/observability/prometheus/prometheus.yml"))
                .contains("app:9090")
                .contains("/actuator/prometheus")
                .contains("alertmanager:9093")
                .contains("alerts.yml");
        assertThat(prometheus).containsKeys("scrape_configs", "rule_files", "alerting");
    }

    @Test
    @DisplayName("경보 규칙은 실제 metric과 p95 histogram을 사용하고 임상 SLO를 가장하지 않는다")
    void alertRulesUseMeasuredMetrics() throws IOException {
        Map<String, Object> alerts = yaml("ops/observability/prometheus/alerts.yml");
        String alertText = text("ops/observability/prometheus/alerts.yml");

        assertThat(alerts).containsKey("groups");
        assertThat(alertText)
                .contains("mio_ai_turn_duration_seconds_bucket")
                .contains("mio_ai_turn_first_substantive_seconds_bucket")
                .contains("mio_judge_input_total")
                .contains("mio_judge_output_total")
                .contains("mio_llm_cost_unpriced_total")
                .contains("MioCrisisRecordFailed", "mio_crisis_records_total")
                .contains("MioRetrievalFailureRatioHigh", "mio_retrieval_outcome_total")
                .contains("MioContractViolationRatioHigh", "mio_ai_contract_results_total")
                .contains("MioSummaryPipelineFailure", "mio_summary_stage_duration_seconds_count")
                .contains("mio_summary_component_total")
                .contains("severity: warning")
                .doesNotContain("clinical", "임상", "REPLACE_ME");
    }

    @Test
    @DisplayName("Alertmanager는 webhook 비밀을 파일로만 읽고 저장소에 URL을 넣지 않는다")
    void alertmanagerReadsWebhookFromSecretFile() throws IOException {
        String config = text("ops/observability/alertmanager/alertmanager.yml");

        assertThat(config)
                .contains("slack_api_url_file: /run/secrets/alertmanager_slack_webhook")
                .contains("send_resolved: true")
                .doesNotContain("hooks.slack.com", "REPLACE_ME");
        assertThat(yaml("ops/observability/alertmanager/alertmanager.yml"))
                .containsKeys("global", "route", "receivers");
    }

    @Test
    @DisplayName("Grafana 최소 대시보드는 안전·지연·비용·실패·계약·고착을 한 화면에 제공한다")
    void dashboardContainsOperationalEvidencePanels() throws IOException {
        JsonNode dashboard = JSON.readTree(text(
                "ops/observability/grafana/dashboards/mio-ai-overview.json"));
        JsonNode panels = dashboard.path("panels");

        assertThat(panels.isArray()).isTrue();
        assertThat(panels.size()).isGreaterThanOrEqualTo(7);

        String dashboardText = dashboard.toString();
        assertThat(dashboardText)
                .contains("Safety")
                .contains("Turn p95")
                .contains("TTFT")
                .contains("Cost")
                .contains("Judge failures")
                .contains("Contract")
                .contains("Stuck work")
                .contains("Summary pipeline")
                .contains("histogram_quantile(0.95")
                .contains("mio_ai_turn_duration_seconds_bucket")
                .contains("mio_ai_turn_llm_ttft_seconds_bucket")
                .contains("mio_ai_turn_first_substantive_seconds_bucket")
                .contains("mio_summary_stage_duration_seconds_bucket")
                .contains("mio_summary_component_total")
                .contains("increase(mio_llm_cost_usd_total[24h])");
    }

    @Test
    @DisplayName("요약 지연의 초 단위가 상태 이벤트 건수에 잘못 적용되지 않는다")
    void summaryPanelUsesSecondsOnlyForLatencySeries() throws IOException {
        JsonNode dashboard = JSON.readTree(text(
                "ops/observability/grafana/dashboards/mio-ai-overview.json"));
        JsonNode summaryPanel = findPanel(dashboard, "Summary pipeline status");

        assertThat(summaryPanel).isNotNull();
        assertThat(summaryPanel.path("fieldConfig").path("defaults").has("unit")).isFalse();
        assertThat(summaryPanel.path("fieldConfig").path("overrides").toString())
                .contains("byFrameRefID", "\"options\":\"A\"", "\"value\":\"s\"");
    }

    @Test
    @DisplayName("운영 설정과 PromQL에 고카디널리티 식별자 label을 두지 않는다")
    void metricConfigurationHasNoHighCardinalityLabels() throws IOException {
        String all = String.join("\n",
                text("ops/observability/prometheus/prometheus.yml"),
                text("ops/observability/prometheus/alerts.yml"),
                text("ops/observability/grafana/dashboards/mio-ai-overview.json"));

        assertThat(all.toLowerCase())
                .doesNotContain("user_id", "session_id", "message_id", "trace_id", "decision_id");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(String relative) throws IOException {
        return YAML.load(text(relative));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private String text(String relative) throws IOException {
        Path path = ROOT.resolve(relative);
        assertThat(path).as("필수 운영 artifact가 없다: " + relative).exists();
        return Files.readString(path);
    }

    private JsonNode findPanel(JsonNode dashboard, String title) {
        for (JsonNode panel : dashboard.path("panels")) {
            if (title.equals(panel.path("title").asText())) {
                return panel;
            }
        }
        return null;
    }
}
