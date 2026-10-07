package com.toni.marketplace.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Request-correlation filter (backlog #44): every request gets an
 * {@code X-Request-ID}, either honored from the incoming header or generated
 * as a UUID when the caller supplies none (or a blank one). The id is:
 * <ul>
 *   <li>echoed on the response under the same header, so clients can quote
 *       it back in bug reports;</li>
 *   <li>placed in the SLF4J MDC under {@code requestId}, so every log line
 *       emitted while the request is being handled carries it — the
 *       {@code logging.pattern.console} in application.yml renders the
 *       {@code %X{requestId}} value.</li>
 * </ul>
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} so the id is attached before
 * any other filter (rate limiting, JWT auth) logs or rejects — a throttled
 * {@code 429} or a {@code 401} still carries the echo header. The MDC entry
 * is removed in a {@code finally} block: servlet containers pool request
 * threads, and a leaked entry would corrupt the next request's logs.
 *
 * <p>Incoming ids are sanitized (CR/LF stripped, trimmed, capped at 128
 * chars) — header values flow into log lines, and a malicious id could
 * otherwise inject fake log entries or flood them.
 *
 * <p>Honest scope: JVM-local correlation only. Nothing propagates the id to
 * downstream services (no W3C traceparent, no Feign/RestClient interceptor),
 * and the MDC is thread-bound, so async handoffs (e.g. {@code @Async}) will
 * not carry it unless explicitly copied.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  static final String REQUEST_ID_HEADER = "X-Request-ID";
  static final String MDC_KEY = "requestId";
  static final int MAX_REQUEST_ID_LENGTH = 128;

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
    String requestId = sanitize(request.getHeader(REQUEST_ID_HEADER));
    if (requestId.isBlank()) {
      requestId = UUID.randomUUID().toString();
    }
    MDC.put(MDC_KEY, requestId);
    try {
      response.setHeader(REQUEST_ID_HEADER, requestId);
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
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
