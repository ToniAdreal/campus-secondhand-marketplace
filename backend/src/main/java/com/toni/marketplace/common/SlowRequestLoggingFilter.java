package com.toni.marketplace.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Slow-request logging (backlog #331).
 *
 * <p>Records one line per request that takes longer than a configurable
 * threshold (default 1000 ms, {@code app.observability.slow-request-threshold}).
 * The line shape is fixed so a log aggregator can parse it without guessing:
 * <pre>
 * slow request method=GET path=/api/items status=200 durationMs=1234 requestId=order-42
 * </pre>
 * The five fields are exactly method, path, status, durationMs and
 * requestId — nothing else. Bodies, query values, headers and tokens are
 * never logged: the path comes from {@code getRequestURI()}, which
 * excludes the query string, and the request id comes from the MDC entry
 * {@link RequestIdFilter} already set (a {@code -} placeholder is used
 * when this filter runs without it, e.g. in isolation).
 *
 * <p>Ordering: {@code HIGHEST_PRECEDENCE + 1}, immediately after
 * {@link RequestIdFilter}, so the MDC keys exist by the time the slow
 * line is written. This filter never puts anything into the MDC itself,
 * so it has nothing to leak — clearing the requestId/traceId/spanId
 * entries stays {@link RequestIdFilter}'s job in its {@code finally}
 * block.
 *
 * <p>Timing uses the application {@link Clock} bean (see AuthConfig), so
 * tests drive it with a controllable clock instead of sleeping. The
 * threshold comparison is strictly-over: a request taking exactly the
 * threshold is not slow. The status is whatever the response reports
 * when the filter unwinds; when downstream throws, the exception still
 * propagates and the slow line is still written (the {@code finally}
 * block), but the container's error handling may set the final status
 * afterwards — the line then shows the pre-error status.
 *
 * <p>Honest scope: this is a log line, not alerting and not a
 * performance profile. It says a request was slow, not why.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SlowRequestLoggingFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(SlowRequestLoggingFilter.class);

  private final Clock clock;
  private final Duration threshold;

  public SlowRequestLoggingFilter(Clock clock,
      @Value("${app.observability.slow-request-threshold:1000ms}") Duration threshold) {
    this.clock = clock;
    this.threshold = threshold;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
    long startMs = clock.millis();
    try {
      chain.doFilter(request, response);
    } finally {
      long durationMs = clock.millis() - startMs;
      if (durationMs > threshold.toMillis()) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        log.warn("slow request method={} path={} status={} durationMs={} requestId={}",
            request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs,
            requestId != null ? requestId : "-");
      }
    }
  }
}
