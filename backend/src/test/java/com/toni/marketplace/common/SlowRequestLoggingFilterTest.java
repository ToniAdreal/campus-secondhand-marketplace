package com.toni.marketplace.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.toni.marketplace.auth.MutableClock;
import jakarta.servlet.ServletException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slow-request logging (backlog #331): only requests strictly over the
 * threshold log, the line carries exactly method/path/status/durationMs/
 * requestId and never a query value, and the filter adds no MDC entries
 * of its own — the requestId/traceId keys are still cleared by
 * RequestIdFilter once a real request completes.
 *
 * <p>Direct style: the filter is constructed with a {@link MutableClock}
 * and a 1000 ms threshold, and the downstream chain advances the clock
 * instead of sleeping. One MockMvc test pins the integrated MDC-clearing
 * behaviour through the real filter pair.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SlowRequestLoggingFilterTest {

  private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
  private static final Duration THRESHOLD = Duration.ofMillis(1000);

  @Autowired
  private MockMvc mockMvc;

  private MutableClock clock;
  private SlowRequestLoggingFilter filter;
  private Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(NOW);
    filter = new SlowRequestLoggingFilter(clock, THRESHOLD);
    logger = (Logger) LoggerFactory.getLogger(SlowRequestLoggingFilter.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
    MDC.clear();
  }

  private MockHttpServletRequest getRequest(String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("GET");
    request.setRequestURI(uri);
    return request;
  }

  @Test
  void underThreshold_staysSilent() throws Exception {
    MockHttpServletRequest request = getRequest("/api/items");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilterInternal(request, response, (req, res) -> {
      clock.advance(Duration.ofMillis(999));
      ((MockHttpServletResponse) res).setStatus(200);
    });

    assertThat(appender.list).isEmpty();
  }

  @Test
  void exactlyAtThreshold_staysSilent() throws Exception {
    // The rule is strictly-over: exactly 1000 ms is not slow.
    MockHttpServletRequest request = getRequest("/api/items");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilterInternal(request, response, (req, res) ->
        clock.advance(Duration.ofMillis(1000)));

    assertThat(appender.list).isEmpty();
  }

  @Test
  void overThreshold_logsOneLineWithAllFiveFieldsAndNoQueryValues() throws Exception {
    MDC.put(RequestIdFilter.MDC_KEY, "req-9");
    MockHttpServletRequest request = getRequest("/api/items");
    request.setQueryString("token=secret-value");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilterInternal(request, response, (req, res) -> {
      clock.advance(Duration.ofMillis(1500));
      ((MockHttpServletResponse) res).setStatus(200);
    });

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    String line = event.getFormattedMessage();
    assertThat(line).contains("method=GET");
    assertThat(line).contains("path=/api/items");
    assertThat(line).contains("status=200");
    assertThat(line).contains("durationMs=1500");
    assertThat(line).contains("requestId=req-9");
    // The query string must never leak into the line.
    assertThat(line).doesNotContain("secret-value");
    assertThat(line).doesNotContain("token=");
  }

  @Test
  void overThresholdWithoutRequestId_logsDashPlaceholder() throws Exception {
    MockHttpServletRequest request = getRequest("/api/items");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilterInternal(request, response, (req, res) ->
        clock.advance(Duration.ofMillis(1200)));

    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage()).contains("requestId=-");
    // The filter invents no MDC entries of its own.
    assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    assertNull(MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY));
  }

  @Test
  void downstreamException_stillLogsWhenSlowAndPropagates() {
    MockHttpServletRequest request = getRequest("/api/items");
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertThatThrownBy(() -> filter.doFilterInternal(request, response, (req, res) -> {
      clock.advance(Duration.ofMillis(1200));
      throw new ServletException("boom");
    })).isInstanceOf(ServletException.class).hasMessageContaining("boom");

    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage()).contains("durationMs=1200");
  }

  @Test
  void mdcKeysAreClearedAfterARealRequest() throws Exception {
    mockMvc.perform(get("/actuator/health")
            .header(RequestIdFilter.REQUEST_ID_HEADER, "slow-mdc-1"))
        .andExpect(status().isOk());

    // RequestIdFilter owns clearing; the slow filter (running inside it)
    // must not leave the keys behind either.
    assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    assertNull(MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY));
    assertNull(MDC.get(RequestIdFilter.SPAN_ID_MDC_KEY));
  }
}
