package com.toni.marketplace.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Request-correlation filter (backlog #44, extended with W3C Trace Context
 * correlation in backlog #214): every request gets an {@code X-Request-ID},
 * either honored from the incoming header or generated as a UUID when the
 * caller supplies none (or a blank one). The id is:
 * <ul>
 *   <li>echoed on the response under the same header, so clients can quote
 *       it back in bug reports;</li>
 *   <li>placed in the SLF4J MDC under {@code requestId}, so every log line
 *       emitted while the request is being handled carries it — the
 *       console pattern in logback-spring.xml renders the
 *       {@code %X{requestId}} value, and the opt-in {@code json-logs}
 *       profile (backlog #137) emits it as a top-level {@code requestId}
 *       JSON field.</li>
 * </ul>
 *
 * <p>W3C trace context (#214): the filter also handles the
 * {@code traceparent} header, dependency-free. An inbound value is parsed
 * with strict format validation (see {@link #parseTraceParent}); a valid
 * value contributes its trace-id, this server mints a fresh span-id for
 * its own span (the inbound parent-id is never reused as our span-id) and
 * the inbound trace-flags are preserved. When the header is absent — or
 * malformed, in which case it is ignored and never echoed — a fresh
 * trace-id and span-id are minted with the sampled flag set. The resulting
 * {@code traceId} and {@code spanId} go into the MDC (logback renders
 * them on every line, and the {@code json-logs} profile emits them as
 * top-level fields), and the response carries a
 * {@code traceparent} of {@code 00-<traceId>-<spanId>-<flags>} so a caller
 * or a downstream hop can continue the same trace.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} so the ids are attached
 * before any other filter (rate limiting, JWT auth) logs or rejects — a
 * throttled {@code 429} or a {@code 401} still carries the echo headers.
 * The MDC entries are removed in a {@code finally} block: servlet
 * containers pool request threads, and a leaked entry would corrupt the
 * next request's logs.
 *
 * <p>Incoming request ids are sanitized (CR/LF stripped, trimmed, capped
 * at 128 chars) — header values flow into log lines, and a malicious id
 * could otherwise inject fake log entries or flood them.
 *
 * <p>Honest scope: this is trace <em>correlation</em>, not distributed
 * tracing. There is no OpenTelemetry exporter, no span reporting and no
 * automatic propagation into outgoing HTTP/database calls — the ids exist
 * so one request can be followed across the nginx → backend → log lines
 * as a single trace, and an OTel exporter can adopt the same ids later.
 * The MDC is thread-bound, so async handoffs (e.g. {@code @Async}) will
 * not carry it unless explicitly copied.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  static final String REQUEST_ID_HEADER = "X-Request-ID";
  static final String MDC_KEY = "requestId";
  static final int MAX_REQUEST_ID_LENGTH = 128;

  static final String TRACEPARENT_HEADER = "traceparent";
  static final String TRACE_ID_MDC_KEY = "traceId";
  static final String SPAN_ID_MDC_KEY = "spanId";

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String MINTED_FLAGS = "01";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
    String requestId = sanitize(request.getHeader(REQUEST_ID_HEADER));
    if (requestId.isBlank()) {
      requestId = UUID.randomUUID().toString();
    }

    String[] parsed = parseTraceParent(request.getHeader(TRACEPARENT_HEADER));
    String traceId = parsed != null ? parsed[0] : mintHex(16);
    String flags = parsed != null ? parsed[1] : MINTED_FLAGS;
    String spanId = mintHex(8);
    String traceparent = "00-" + traceId + "-" + spanId + "-" + flags;

    MDC.put(MDC_KEY, requestId);
    MDC.put(TRACE_ID_MDC_KEY, traceId);
    MDC.put(SPAN_ID_MDC_KEY, spanId);
    try {
      response.setHeader(REQUEST_ID_HEADER, requestId);
      response.setHeader(TRACEPARENT_HEADER, traceparent);
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
      MDC.remove(TRACE_ID_MDC_KEY);
      MDC.remove(SPAN_ID_MDC_KEY);
    }
  }

  /**
   * Strictly parses a W3C {@code traceparent} header
   * ({@code version-traceId-parentId-traceFlags}). Returns
   * {@code [traceId, flags]} for a valid value, or {@code null} when the
   * value is absent or malformed — a malformed value is ignored wholesale
   * and never echoed, so a hostile or broken header cannot inject ids
   * into logs or responses.
   *
   * <p>Validation, per the W3C Trace Context spec: every field is
   * lowercase hex of its exact length; version {@code ff} is forbidden;
   * an all-zero trace-id or parent-id is forbidden; version {@code 00}
   * must have exactly four fields (55 chars total). A future (non-{@code
   * 00}) version may carry extra trailing fields — those are ignored and
   * the first four fields are validated as above, as the spec requires
   * parsers to do.
   */
  static String[] parseTraceParent(String raw) {
    if (raw == null) {
      return null;
    }
    String value = raw.trim();
    String[] parts = value.split("-", -1);
    if (parts.length < 4) {
      return null;
    }
    String version = parts[0];
    if (!isLowerHex(version, 2) || version.equals("ff")) {
      return null;
    }
    if (version.equals("00") && (parts.length != 4 || value.length() != 55)) {
      return null;
    }
    String traceId = parts[1];
    String parentId = parts[2];
    String flags = parts[3];
    if (!isLowerHex(traceId, 32) || traceId.chars().allMatch(c -> c == '0')) {
      return null;
    }
    if (!isLowerHex(parentId, 16) || parentId.chars().allMatch(c -> c == '0')) {
      return null;
    }
    if (!isLowerHex(flags, 2)) {
      return null;
    }
    return new String[] {traceId, flags};
  }

  private static boolean isLowerHex(String value, int length) {
    if (value.length() != length) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
        return false;
      }
    }
    return true;
  }

  /** Mints {@code bytes} of random lowercase hex, never all-zero. */
  private static String mintHex(int bytes) {
    byte[] buf = new byte[bytes];
    do {
      RANDOM.nextBytes(buf);
    } while (isAllZero(buf));
    return HexFormat.of().formatHex(buf);
  }

  private static boolean isAllZero(byte[] buf) {
    for (byte b : buf) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Strips carriage returns and line feeds (log/header injection hardening),
   * trims whitespace, and caps the length so a hostile id cannot flood the
   * logs. Never returns null.
   */
  private static String sanitize(String raw) {
    if (raw == null) {
      return "";
    }
    String clean = raw.replace('\r', ' ').replace('\n', ' ').trim();
    return clean.length() > MAX_REQUEST_ID_LENGTH
        ? clean.substring(0, MAX_REQUEST_ID_LENGTH)
        : clean;
  }
}
