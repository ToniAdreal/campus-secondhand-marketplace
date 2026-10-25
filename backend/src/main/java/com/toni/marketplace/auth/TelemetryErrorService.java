package com.toni.marketplace.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Frontend error telemetry intake (backlog #223): parsing, sanitizing,
 * logging and counting for {@code POST /api/telemetry/errors}. It mirrors
 * the CSP report intake (backlog #204) in shape — a public,
 * body-capped, per-IP-throttled beacon endpoint — because the problem is
 * the same: before #223 the route-level {@code ErrorBoundary} logged a
 * caught render error with {@code console.error} only, so a crash in a
 * real browser was invisible to the operator.
 *
 * <p>Privacy / log hygiene is structural, as in #204: the only values
 * that ever reach the log line or the metrics tag set are the outputs of
 * the sanitizers below —
 *
 * <ul>
 *   <li>the error name, reduced to a known JavaScript error name
 *       (anything unrecognized collapses to {@code other}, which also
 *       bounds the counter's tag cardinality: a beacon sender cannot
 *       mint unbounded metric series by inventing names);</li>
 *   <li>the route <em>template</em> (e.g. {@code /items/:id}), never a
 *       concrete path: numeric and UUID segments are replaced with
 *       {@code :id}, unknown segments are dropped, and any query string
 *       or fragment is stripped before segment parsing — a route can
 *       therefore never carry an id, token or search term;</li>
 *   <li>the app version, reduced to a short dotted identifier, or
 *       {@code unknown}.</li>
 * </ul>
 *
 * <p>The error message, any stack text, stack URLs, and every other
 * beacon field are never logged, counted or persisted — a message or a
 * stack frame can carry user data, tokens and page URLs, and the intake
 * has no way to tell a benign frame from a leaking one, so it drops
 * them all by construction (the service reads only {@code name},
 * {@code route} and {@code version} from the JSON and ignores the rest).
 *
 * <p>Honest scope: counts and names only — no session replay, no stack
 * capture, no third-party error service, nothing persisted, no alert
 * fires from inside the app (the counter joins the existing Prometheus
 * export, where an operator's own rules can watch it), and a beacon is a
 * client's claim, not proof: any client can POST a fabricated beacon to
 * this public endpoint, so the data is a signal for spotting a broken
 * release, never evidence about a user.
 */
@Service
public class TelemetryErrorService {

  private static final Logger log = LoggerFactory.getLogger(TelemetryErrorService.class);

  /** Counter name on the Prometheus export, tagged by sanitized error name. */
  static final String COUNTER_NAME = "frontend_errors_total";

  /**
   * Error names a real browser runtime produces. Anything else collapses
   * to {@code other}: the set bounds {@code frontend_errors_total}'s tag
   * cardinality and strips any decoration a sender attached.
   * {@code UnhandledRejection} is the frontend's own label for a rejected
   * promise whose reason is not an {@code Error} (see
   * {@code frontend/src/api/telemetry.ts}).
   */
  private static final Set<String> KNOWN_ERROR_NAMES = Set.of(
      "Error", "TypeError", "ReferenceError", "RangeError", "SyntaxError",
      "EvalError", "URIError", "AggregateError", "DOMException",
      "UnhandledRejection");

  private final ObjectMapper mapper;
  private final MeterRegistry registry;

  public TelemetryErrorService(ObjectMapper mapper, MeterRegistry registry) {
    this.mapper = mapper;
    this.registry = registry != null ? registry : new SimpleMeterRegistry();
  }

  /** Thrown when the body is not a recognizable error beacon. */
  public static class MalformedTelemetryException extends RuntimeException {
    public MalformedTelemetryException(String message) {
      super(message);
    }
  }

  /** One sanitized beacon: error name + route template + app version, nothing else. */
  record SanitizedError(String name, String route, String version) {}

  /**
   * Parses {@code body}, then logs and counts the beacon.
   *
   * @throws MalformedTelemetryException when the body is not JSON, is not
   *     an object, or carries no usable {@code name} field
   */
  public void accept(byte[] body) {
    SanitizedError error = parse(body);
    log.warn("frontend error name={} route={} version={}",
        error.name(), error.route(), error.version());
    Counter.builder(COUNTER_NAME)
        .description("Frontend error beacons received, by sanitized error name")
        .tag("errorName", error.name())
        .register(registry)
        .increment();
  }

  private SanitizedError parse(byte[] body) {
    JsonNode root;
    try {
      root = mapper.readTree(body);
    } catch (java.io.IOException e) {
      throw new MalformedTelemetryException("body is not valid JSON");
    }
    if (root == null || !root.isObject()) {
      throw new MalformedTelemetryException("beacon is not a JSON object");
    }
    String rawName = textOrNull(root, "name");
    if (rawName == null || rawName.isBlank()) {
      throw new MalformedTelemetryException("beacon carries no error name");
    }
    return new SanitizedError(
        sanitizeName(rawName),
        sanitizeRoute(textOrNull(root, "route")),
        sanitizeVersion(textOrNull(root, "version")));
  }

  private static String textOrNull(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.asText() : null;
  }

  /**
   * Reduces a raw error name to a known JavaScript error name. Only the
   * first whitespace-separated token is considered, characters outside
   * {@code [A-Za-z]} are stripped, the result is capped at 64 chars, and
   * anything not in {@link #KNOWN_ERROR_NAMES} collapses to {@code other}.
   * A missing value cannot reach here (the parser rejects it); a value
   * that cleans to nothing is {@code unknown}.
   */
  static String sanitizeName(String raw) {
    if (raw == null || raw.isBlank()) {
      return "unknown";
    }
    String token = raw.trim().split("\\s+")[0];
    StringBuilder cleaned = new StringBuilder(token.length());
    for (char c : token.toCharArray()) {
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
        cleaned.append(c);
      }
    }
    String name = cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned.toString();
    if (name.isEmpty()) {
      return "unknown";
    }
    return KNOWN_ERROR_NAMES.contains(name) ? name : "other";
  }

  /**
   * Reduces a raw route value to a route template. The query string and
   * fragment are stripped first; then each segment is kept only if it is
   * a static word ({@code [a-z0-9-]+}) or the template parameter
   * {@code :id}. Numeric and UUID-like segments become {@code :id};
   * anything else is dropped. A value that yields no usable segment, or
   * that does not start with {@code /}, collapses to {@code unknown};
   * the result is capped at 128 chars and 8 segments, bounding both log
   * width and cardinality.
   */
  static String sanitizeRoute(String raw) {
    if (raw == null || raw.isBlank()) {
      return "unknown";
    }
    String value = raw.trim();
    int cut = value.length();
    int query = value.indexOf('?');
    if (query != -1) {
      cut = Math.min(cut, query);
    }
    int fragment = value.indexOf('#');
    if (fragment != -1) {
      cut = Math.min(cut, fragment);
    }
    value = value.substring(0, cut);
    if (!value.startsWith("/")) {
      return "unknown";
    }
    String[] segments = value.split("/");
    StringBuilder template = new StringBuilder();
    int kept = 0;
    for (String segment : segments) {
      if (segment.isEmpty() || kept >= 8) {
        continue;
      }
      if (segment.equals(":id")) {
        template.append("/").append(":id");
        kept++;
      } else if (segment.matches("[a-z0-9\\-]+")) {
        // A purely numeric segment is an id, not a route word.
        if (segment.matches("[0-9]+")) {
          template.append("/").append(":id");
        } else {
          template.append("/").append(segment.toLowerCase(Locale.ROOT));
        }
        kept++;
      } else if (segment.matches("[0-9a-fA-F\\-]{8,}")) {
        template.append("/").append(":id");
        kept++;
      }
      // Anything else (a slug, a token, an encoded value) is dropped:
      // it can only be user data, never a route word.
    }
    if (template.length() == 0) {
      return "unknown";
    }
    String route = template.toString();
    return route.length() > 128 ? route.substring(0, 128) : route;
  }

  /**
   * Reduces a raw app version to a short dotted identifier
   * ({@code [A-Za-z0-9._-]+}, capped at 32 chars); anything else — a
   * missing value, a value with spaces or URL characters — collapses to
   * {@code unknown}.
   */
  static String sanitizeVersion(String raw) {
    if (raw == null || raw.isBlank()) {
      return "unknown";
    }
    String value = raw.trim();
    if (value.length() > 32) {
      value = value.substring(0, 32);
    }
    return value.matches("[A-Za-z0-9._\\-]+") ? value : "unknown";
  }
}
