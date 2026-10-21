package com.toni.marketplace.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CSP violation report intake (backlog #204): parsing, sanitizing,
 * logging and counting for {@code POST /api/security/csp-report}.
 *
 * <p>Before #204 the opt-in Content-Security-Policy (backlog #80) was
 * fire-and-forget: the header went out, but a violation in a real
 * browser was visible nowhere. This service is the receiving half. Two
 * wire shapes are tolerated, because browsers send both:
 *
 * <ul>
 *   <li>the legacy {@code report-uri} shape — a single object with a
 *       {@code csp-report} member carrying hyphenated fields
 *       ({@code violated-directive}, {@code blocked-uri}, …);</li>
 *   <li>the Reporting API shape — an array of report objects whose
 *       {@code body} member carries camelCase fields
 *       ({@code violatedDirective}, {@code blockedURL}, …). A single
 *       non-array object with a {@code body} member is accepted too.</li>
 * </ul>
 *
 * <p>Privacy / log hygiene is the point of the intake, and it is
 * structural rather than best-effort: the only values that ever reach
 * the log line or the metrics tag set are the outputs of
 * {@link #sanitizeDirective} and {@link #sanitizeBlockedUri} —
 *
 * <ul>
 *   <li>the violated directive, reduced to a known CSP directive name
 *       (anything unrecognized collapses to {@code other}, which also
 *       bounds the counter's tag cardinality: a report sender cannot
 *       mint unbounded metric series by inventing directive strings);</li>
 *   <li>the blocked URI reduced to its <em>origin</em>
 *       ({@code scheme://host[:port]}). The path, query and fragment —
 *       which can carry tokens, ids and user data — are dropped by
 *       construction: the sanitizer rebuilds the string from the parsed
 *       scheme/host/port and never touches the raw value again.
 *       Scheme-only pseudo values browsers actually send
 *       ({@code inline}, {@code eval}, {@code data}, {@code blob}, …)
 *       are kept as bare tokens; they carry no path by definition.</li>
 * </ul>
 *
 * <p>The raw body, the request headers and every other report field
 * (notably {@code document-uri}, which is a full page URL) are never
 * logged. One WARN line is emitted per report entry plus one
 * {@code csp_reports_total} increment tagged with the sanitized
 * directive.
 *
 * <p>Honest scope: reports are counted and logged only — nothing is
 * persisted, no alert fires from inside the app (the counter joins the
 * existing Prometheus export, where an operator's own rules can watch
 * it), and a report is a browser's claim, not proof: any client can
 * POST a fabricated report to this public endpoint, so the data is a
 * signal for spotting a broken policy, never evidence about a user.
 */
@Service
public class CspReportService {

  private static final Logger log = LoggerFactory.getLogger(CspReportService.class);

  /** Counter name on the Prometheus export, tagged by sanitized directive. */
  static final String COUNTER_NAME = "csp_reports_total";

  /**
   * Directive names a real browser can report. Anything else collapses
   * to {@code other}: the set bounds {@code csp_reports_total}'s tag
   * cardinality and strips any decoration a sender attached.
   */
  private static final Set<String> KNOWN_DIRECTIVES = Set.of(
      "base-uri", "block-all-mixed-content", "child-src", "connect-src",
      "default-src", "fenced-frame-src", "font-src", "form-action",
      "frame-ancestors", "frame-src", "img-src", "manifest-src",
      "media-src", "navigate-to", "object-src", "prefetch-src",
      "report-to", "report-uri", "require-trusted-types-for", "sandbox",
      "script-src", "script-src-attr", "script-src-elem", "style-src",
      "style-src-attr", "style-src-elem", "trusted-types",
      "upgrade-insecure-requests", "wasm-unsafe-eval", "worker-src");

  private final ObjectMapper mapper;
  private final MeterRegistry registry;

  public CspReportService(ObjectMapper mapper, MeterRegistry registry) {
    this.mapper = mapper;
    this.registry = registry != null ? registry : new SimpleMeterRegistry();
  }

  /** Thrown when the body is not a recognizable CSP report in either shape. */
  public static class MalformedCspReportException extends RuntimeException {
    public MalformedCspReportException(String message) {
      super(message);
    }
  }

  /** One sanitized report entry: directive + blocked origin, nothing else. */
  record SanitizedReport(String directive, String blockedOrigin) {}

  /**
   * Parses {@code body}, then logs and counts each entry it carries.
   *
   * @return the number of report entries accepted
   * @throws MalformedCspReportException when the body is not JSON, or
   *     carries no recognizable report entry in either shape
   */
  public int accept(byte[] body) {
    List<SanitizedReport> reports = parse(body);
    for (SanitizedReport report : reports) {
      log.warn("csp violation report directive={} blockedOrigin={}",
          report.directive(), report.blockedOrigin());
      Counter.builder(COUNTER_NAME)
          .description("CSP violation reports received, by violated directive")
          .tag("directive", report.directive())
          .register(registry)
          .increment();
    }
    return reports.size();
  }

  private List<SanitizedReport> parse(byte[] body) {
    JsonNode root;
    try {
      root = mapper.readTree(body);
    } catch (java.io.IOException e) {
      throw new MalformedCspReportException("body is not valid JSON");
    }
    if (root == null || root.isNull()) {
      throw new MalformedCspReportException("empty report body");
    }
    List<SanitizedReport> reports = new ArrayList<>();
    if (root.isArray()) {
      for (JsonNode element : root) {
        SanitizedReport entry = reportingApiEntry(element);
        if (entry != null) {
          reports.add(entry);
        }
      }
    } else if (root.isObject() && root.hasNonNull("csp-report")) {
      JsonNode legacy = root.get("csp-report");
      if (!legacy.isObject()) {
        throw new MalformedCspReportException("csp-report member is not an object");
      }
      String legacyDirective = firstText(legacy, "violated-directive", "effective-directive");
      String legacyBlocked = textOrNull(legacy, "blocked-uri");
      if (legacyDirective == null && legacyBlocked == null) {
        throw new MalformedCspReportException("csp-report member carries no fields");
      }
      reports.add(new SanitizedReport(
          sanitizeDirective(legacyDirective),
          sanitizeBlockedUri(legacyBlocked)));
    } else if (root.isObject()) {
      SanitizedReport entry = reportingApiEntry(root);
      if (entry != null) {
        reports.add(entry);
      }
    } else {
      throw new MalformedCspReportException("unrecognized report shape");
    }
    if (reports.isEmpty()) {
      throw new MalformedCspReportException("report carries no entries");
    }
    return reports;
  }

  /**
   * One Reporting API entry: fields live under {@code body} in the real
   * shape; a bare object with the fields at top level is tolerated too.
   * Returns {@code null} for a report of another type sharing the
   * endpoint (network-error, deprecation, …) — those are skipped, not
   * counted as CSP violations.
   */
  private static SanitizedReport reportingApiEntry(JsonNode element) {
    if (element == null || !element.isObject()) {
      throw new MalformedCspReportException("report entry is not an object");
    }
    JsonNode body = element.hasNonNull("body") && element.get("body").isObject()
        ? element.get("body") : element;
    String type = textOrNull(element, "type");
    if (type != null && !"csp-violation".equals(type)) {
      return null;
    }
    String directiveRaw = firstText(body, "violatedDirective", "effectiveDirective");
    String blockedRaw = firstText(body, "blockedURL", "blockedUrl");
    if (directiveRaw == null && blockedRaw == null) {
      throw new MalformedCspReportException("report entry carries no csp fields");
    }
    return new SanitizedReport(
        sanitizeDirective(directiveRaw),
        sanitizeBlockedUri(blockedRaw));
  }

  private static String textOrNull(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.asText() : null;
  }

  private static String firstText(JsonNode node, String first, String second) {
    String value = textOrNull(node, first);
    return value != null ? value : textOrNull(node, second);
  }

  /**
   * Reduces a raw violated-directive value to a known directive name.
   * Only the first whitespace-separated token is considered (a value
   * like {@code "script-src 'unsafe-inline'"} names {@code script-src}),
   * characters outside {@code [a-z0-9-]} are stripped, the result is
   * capped at 64 chars, and anything not in {@link #KNOWN_DIRECTIVES}
   * — including a missing value — collapses to a bounded fallback
   * ({@code unknown} when nothing usable was sent, {@code other} when
   * something unrecognized was).
   */
  static String sanitizeDirective(String raw) {
    if (raw == null || raw.isBlank()) {
      return "unknown";
    }
    String token = raw.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
    StringBuilder cleaned = new StringBuilder(token.length());
    for (char c : token.toCharArray()) {
      if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
        cleaned.append(c);
      }
    }
    String directive = cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned.toString();
    if (directive.isEmpty()) {
      return "unknown";
    }
    return KNOWN_DIRECTIVES.contains(directive) ? directive : "other";
  }

  /**
   * Reduces a raw blocked-uri value to its origin
   * ({@code scheme://host[:port]}), or to a bare pseudo token
   * ({@code inline}, {@code eval}, {@code data}, …) when the value is
   * not a hierarchical URL. The path, query, fragment and any userinfo
   * are never carried over — the result is rebuilt from the parsed
   * scheme/host/port only. Unparseable values collapse to
   * {@code other}; a missing value to {@code unknown}.
   */
  static String sanitizeBlockedUri(String raw) {
    if (raw == null || raw.isBlank()) {
      return "unknown";
    }
    String value = raw.trim();
    // Bare token with no URL structure (inline/eval/data/blob/about…):
    // browsers send exactly these for non-network violations, and a
    // single token cannot carry a path.
    if (value.matches("[A-Za-z][A-Za-z0-9+.\\-]*")) {
      return value.toLowerCase(Locale.ROOT);
    }
    try {
      URI uri = new URI(value);
      String scheme = uri.getScheme();
      if (scheme == null) {
        return "other";
      }
      String host = uri.getHost();
      if (host == null) {
        // Opaque URI (data:…, blob:… with an inner URL, javascript:…):
        // the scheme is the only part that is safe to keep.
        return scheme.toLowerCase(Locale.ROOT);
      }
      StringBuilder origin = new StringBuilder()
          .append(scheme.toLowerCase(Locale.ROOT)).append("://")
          .append(host.toLowerCase(Locale.ROOT));
      if (uri.getPort() != -1) {
        origin.append(':').append(uri.getPort());
      }
      return origin.toString();
    } catch (URISyntaxException e) {
      return "other";
    }
  }
}
