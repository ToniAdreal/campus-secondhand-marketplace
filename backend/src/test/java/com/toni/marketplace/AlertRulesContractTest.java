package com.toni.marketplace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Backlog #184 — the Prometheus alert rules are a contract, not prose:
 * docs/alert-rules.yml must parse, every alert must carry a summary and
 * a runbook anchor, docs/runbook.md must have a section per alert, and
 * — the load-bearing pin — every metric an expression references must
 * be a metric this application actually emits. App-defined metrics are
 * pinned back to the {@code *.builder("...")} definition in their
 * Metrics class; the one framework series (http.server.requests) is
 * pinned to the export documented in docs/smoke-test.md. A rule that
 * invents a metric fails here instead of paging nobody in production.
 */
class AlertRulesContractTest {

  /** Prometheus identifiers that are PromQL syntax or label names, not metrics. */
  private static final Set<String> NOT_METRICS = Set.of(
      "sum", "rate", "increase", "clamp_min", "status");

  private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

  /** Alert name → the exact metric set its expression may reference. */
  private static final Map<String, Set<String>> EXPECTED_ALERTS = new LinkedHashMap<>();

  static {
    EXPECTED_ALERTS.put("AuthLoginFailureRateHigh",
        Set.of("auth_login_failure_total", "auth_login_success_total"));
    EXPECTED_ALERTS.put("AuthLockoutSurge",
        Set.of("auth_lockout_triggered_total"));
    EXPECTED_ALERTS.put("AuthRefreshTheftDetected",
        Set.of("auth_refresh_theft_detected_total"));
    EXPECTED_ALERTS.put("PaymentDeclineRateHigh",
        Set.of("payments_declined_total", "orders_paid_total"));
    EXPECTED_ALERTS.put("WebhookMismatchRateHigh",
        Set.of("payments_webhook_mismatched_total", "payments_webhook_matched_total"));
    EXPECTED_ALERTS.put("Http5xxRateHigh",
        Set.of("http_server_requests_seconds_count"));
  }

  /** Prometheus series → the Micrometer builder name that defines it. */
  private static final Map<String, String> BUILDER_NAMES = new LinkedHashMap<>();

  static {
    BUILDER_NAMES.put("auth_login_failure_total", "auth.login.failure");
    BUILDER_NAMES.put("auth_login_success_total", "auth.login.success");
    BUILDER_NAMES.put("auth_lockout_triggered_total", "auth.lockout.triggered");
    BUILDER_NAMES.put("auth_refresh_theft_detected_total", "auth.refresh.theft_detected");
    BUILDER_NAMES.put("payments_declined_total", "payments.declined");
    BUILDER_NAMES.put("orders_paid_total", "orders.paid");
    BUILDER_NAMES.put("payments_webhook_mismatched_total", "payments.webhook.mismatched");
    BUILDER_NAMES.put("payments_webhook_matched_total", "payments.webhook.matched");
  }

  /** The Metrics class each builder name must be found in. */
  private static final Map<String, String> BUILDER_SOURCES = new LinkedHashMap<>();

  static {
    BUILDER_SOURCES.put("auth.login.failure", "auth/AuthMetrics.java");
    BUILDER_SOURCES.put("auth.login.success", "auth/AuthMetrics.java");
    BUILDER_SOURCES.put("auth.lockout.triggered", "auth/AuthMetrics.java");
    BUILDER_SOURCES.put("auth.refresh.theft_detected", "auth/AuthMetrics.java");
    BUILDER_SOURCES.put("payments.declined", "order/OrderMetrics.java");
    BUILDER_SOURCES.put("orders.paid", "order/OrderMetrics.java");
    BUILDER_SOURCES.put("payments.webhook.mismatched", "order/WebhookMetrics.java");
    BUILDER_SOURCES.put("payments.webhook.matched", "order/WebhookMetrics.java");
  }

  private static Path repoRoot() {
    Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    for (int i = 0; i < 4 && dir != null; i++) {
      if (Files.exists(dir.resolve("docs/alert-rules.yml"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("docs/alert-rules.yml not found above user.dir");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> loadRules() throws IOException {
    try (InputStream in = Files.newInputStream(repoRoot().resolve("docs/alert-rules.yml"))) {
      Map<String, Object> doc = new Yaml().load(in);
      assertThat(doc).as("alert-rules.yml parses to a mapping").isNotNull();
      List<Map<String, Object>> groups =
          (List<Map<String, Object>>) doc.get("groups");
      assertThat(groups).as("exactly one rule group").hasSize(1);
      return (List<Map<String, Object>>) groups.get(0).get("rules");
    }
  }

  private static Set<String> metricsIn(String expr) {
    Set<String> metrics = new LinkedHashSet<>();
    Matcher matcher = IDENTIFIER.matcher(expr);
    while (matcher.find()) {
      String token = matcher.group();
      if (token.contains("_") && !NOT_METRICS.contains(token)) {
        metrics.add(token);
      }
    }
    return metrics;
  }

  @Test
  void rulesParseAndCarrySummarySeverityAndRunbookAnchor() throws IOException {
    List<Map<String, Object>> rules = loadRules();
    List<String> names = new ArrayList<>();
    for (Map<String, Object> rule : rules) {
      String alert = (String) rule.get("alert");
      names.add(alert);
      assertThat(rule.get("expr")).as(alert + " expr").isNotNull();
      assertThat(rule.get("for")).as(alert + " for").isNotNull();
      @SuppressWarnings("unchecked")
      Map<String, Object> labels = (Map<String, Object>) rule.get("labels");
      assertThat(labels).as(alert + " labels").isNotNull();
      assertThat(labels.get("severity")).as(alert + " severity").isNotNull();
      @SuppressWarnings("unchecked")
      Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
      assertThat(annotations).as(alert + " annotations").isNotNull();
      assertThat((String) annotations.get("summary"))
          .as(alert + " summary")
          .isNotBlank();
      assertThat((String) annotations.get("runbook_url"))
          .as(alert + " runbook anchor")
          .endsWith("docs/runbook.md#" + alert.toLowerCase());
    }
    assertThat(names)
        .as("the pinned alert set — no more, no fewer")
        .containsExactlyElementsOf(EXPECTED_ALERTS.keySet());
  }

  @Test
  void everyReferencedMetricIsOneTheAppActuallyEmits() throws IOException {
    for (Map<String, Object> rule : loadRules()) {
      String alert = (String) rule.get("alert");
      assertThat(metricsIn((String) rule.get("expr")))
          .as(alert + " references exactly its pinned metrics")
          .isEqualTo(EXPECTED_ALERTS.get(alert));
    }
    Path mainJava = repoRoot().resolve("backend/src/main/java/com/toni/marketplace");
    for (Map.Entry<String, String> entry : BUILDER_NAMES.entrySet()) {
      String source = Files.readString(mainJava.resolve(BUILDER_SOURCES.get(entry.getValue())));
      assertThat(source)
          .as(entry.getKey() + " is defined as " + entry.getValue())
          .contains(".builder(\"" + entry.getValue() + "\")");
    }
    // The one framework series: pinned to the export the smoke doc records.
    String smoke = Files.readString(repoRoot().resolve("docs/smoke-test.md"));
    assertThat(smoke)
        .as("http.server.requests is part of the documented Prometheus export")
        .contains("http.server.requests");
  }

  @Test
  void runbookHasASectionPerAlertAndReadmePointsAtBothFiles() throws IOException {
    String runbook = Files.readString(repoRoot().resolve("docs/runbook.md"));
    for (String alert : EXPECTED_ALERTS.keySet()) {
      assertThat(runbook).as("runbook section for " + alert).contains("## " + alert);
      assertThat(runbook).as("runbook says what " + alert + " is not")
          .contains("What it is NOT");
    }
    assertThat(runbook)
        .as("runbook keeps the honest no-Alertmanager scope")
        .contains("does **not** deploy or bundle Prometheus or Alertmanager");
    String readme = Files.readString(repoRoot().resolve("README.md"));
    assertThat(readme).contains("docs/alert-rules.yml").contains("docs/runbook.md");
    assertThat(readme)
        .as("the stale no-alerting limitation is gone")
        .doesNotContain("No alerting — Prometheus gives metrics, not alerts");
  }
}
