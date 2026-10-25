package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Frontend error telemetry intake (backlog #223): POST
 * /api/telemetry/errors accepts a beacon without any credentials,
 * answers 204, counts {@code frontend_errors_total} per sanitized error
 * name, and logs only the name + the route template + the app version —
 * the message, the stack, stack URLs' query strings, storage/cookie data
 * and any concrete route id must never reach the log (pinned here with
 * a logback {@code ListAppender} on the {@link TelemetryErrorService}
 * logger, the {@code RefreshTheftLoggingTest} / {@code CspReportTest}
 * pattern). Oversize bodies answer the 413 envelope, malformed/empty
 * bodies the 400 envelope.
 *
 * <p>The shared test profile disables the auth rate limiter, so these
 * tests exercise the intake with the telemetry bucket passing through;
 * the dedicated {@code TelemetryErrorRateLimitTest} below re-enables it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TelemetryErrorTest {

  // Deliberately hostile beacon: the message carries a secret, the stack
  // carries a URL with a query token, the route carries a concrete id
  // plus a query, and an extra field smuggles storage/cookie-shaped data.
  // None of it may survive into the log or the counter tags.
  private static final String HOSTILE_BEACON = """
      {"name":"TypeError",
       "message":"Cannot read properties of undefined (reading 'token') — session=abc123secret",
       "stack":"at render (https://market.example/items/42?session=abc123secret:10:5)",
       "route":"/items/42?session=abc123secret",
       "version":"0.1.0",
       "storage":{"localStorageToken":"abc123secret","cookie":"refresh=abc123secret"}}
      """;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private MeterRegistry registry;

  private Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attachAppender() {
    logger = (Logger) LoggerFactory.getLogger(TelemetryErrorService.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
  }

  private double counter(String errorName) {
    Counter counter = registry.find(TelemetryErrorService.COUNTER_NAME)
        .tag("errorName", errorName).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private String allLoggedMessages() {
    StringBuilder out = new StringBuilder();
    for (ILoggingEvent event : appender.list) {
      out.append(event.getFormattedMessage()).append('\n');
    }
    return out.toString();
  }

  @Test
  void beaconIsAcceptedCountedAndLoggedSanitized() throws Exception {
    double before = counter("TypeError");

    mockMvc.perform(post("/api/telemetry/errors")
            .contentType(MediaType.APPLICATION_JSON)
            .content(HOSTILE_BEACON))
        .andExpect(status().isNoContent());

    assertThat(counter("TypeError")).isEqualTo(before + 1);
    String logged = allLoggedMessages();
    assertThat(logged).contains("TypeError");
    assertThat(logged).contains("/items/:id");
    assertThat(logged).contains("0.1.0");
    // The privacy pin: no secret, no message text, no stack URL, no
    // concrete id, no query string may appear in the log.
    assertThat(logged).doesNotContain("abc123secret");
    assertThat(logged).doesNotContain("Cannot read properties");
    assertThat(logged).doesNotContain("session=");
    assertThat(logged).doesNotContain("/items/42");
    assertThat(logged).doesNotContain("localStorageToken");
    assertThat(logged).doesNotContain("refresh=");
  }

  @Test
  void unknownErrorNameCollapsesToOtherAndStillCounts() throws Exception {
    double before = counter("other");

    mockMvc.perform(post("/api/telemetry/errors")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"WeirdCustomError\",\"route\":\"/login\",\"version\":\"0.1.0\"}"))
        .andExpect(status().isNoContent());

    assertThat(counter("other")).isEqualTo(before + 1);
    assertThat(allLoggedMessages()).contains("other");
  }

  @Test
  void missingNameIs400() throws Exception {
    mockMvc.perform(post("/api/telemetry/errors")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"route\":\"/login\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void nonJsonBodyIs400() throws Exception {
    mockMvc.perform(post("/api/telemetry/errors")
            .contentType(MediaType.APPLICATION_JSON)
            .content("this is not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void emptyBodyIs400() throws Exception {
    mockMvc.perform(post("/api/telemetry/errors")
            .contentType(MediaType.APPLICATION_JSON)
            .content(""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void oversizeBodyIs413() throws Exception {
    // Default cap is 4096 bytes; a body of short-name repeats past it
    // must be answered before any parsing happens.
    String oversize = "{\"name\":\"Error\",\"route\":\"/"
        + "a".repeat(5000) + "\"}";
    mockMvc.perform(post("/api/telemetry/errors")
            .contentType(MediaType.APPLICATION_JSON)
            .content(oversize))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value(413));
  }

  @Test
  void routeWithoutLeadingSlashCollapsesToUnknown() throws Exception {
    mockMvc.perform(post("/api/telemetry/errors")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Error\",\"route\":\"items/42\"}"))
        .andExpect(status().isNoContent());
    assertThat(allLoggedMessages()).contains("route=unknown");
  }
}

/**
 * Rate-limit half of backlog #223: with the limiter re-enabled and the
 * telemetry bucket pinned to 3/minute, the fourth beacon from one IP
 * answers the 429 envelope with {@code Retry-After}. Each test uses a
 * fresh client IP (the buckets are in-memory and survive across tests in
 * this class), mirroring {@code CspReportRateLimitTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.telemetry-error-attempts-per-minute=3"
})
class TelemetryErrorRateLimitTest {

  private static final AtomicInteger IP_SEQ = new AtomicInteger(1);

  private static final String BEACON = "{\"name\":\"Error\",\"route\":\"/login\",\"version\":\"0.1.0\"}";

  @Autowired
  private MockMvc mockMvc;

  private static RequestPostProcessor fromIp(String ip) {
    return request -> {
      request.setRemoteAddr(ip);
      return request;
    };
  }

  @Test
  void fourthBeaconInAMinuteIsThrottledWith429Envelope() throws Exception {
    String ip = "10.223.0." + IP_SEQ.getAndIncrement();
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/telemetry/errors")
              .with(fromIp(ip))
              .contentType(MediaType.APPLICATION_JSON)
              .content(BEACON))
          .andExpect(status().isNoContent());
    }
    mockMvc.perform(post("/api/telemetry/errors")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content(BEACON))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"));
  }

  @Test
  void throttlingIsPerIp() throws Exception {
    String ip = "10.223.0." + IP_SEQ.getAndIncrement();
    String otherIp = "10.223.0." + IP_SEQ.getAndIncrement();
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/telemetry/errors")
              .with(fromIp(ip))
              .contentType(MediaType.APPLICATION_JSON)
              .content(BEACON))
          .andExpect(status().isNoContent());
    }
    // A different IP still has its own full bucket.
    mockMvc.perform(post("/api/telemetry/errors")
            .with(fromIp(otherIp))
            .contentType(MediaType.APPLICATION_JSON)
            .content(BEACON))
        .andExpect(status().isNoContent());
  }

  @Test
  void telemetryBucketDoesNotStarveCspBucket() throws Exception {
    // The telemetry bucket is its own bucket: exhausting it must not
    // consume the CSP bucket from the same IP (mirroring the login /
    // refresh independence tests).
    String ip = "10.223.0." + IP_SEQ.getAndIncrement();
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/telemetry/errors")
              .with(fromIp(ip))
              .contentType(MediaType.APPLICATION_JSON)
              .content(BEACON))
          .andExpect(status().isNoContent());
    }
    mockMvc.perform(post("/api/security/csp-report")
            .with(fromIp(ip))
            .contentType("application/csp-report")
            .content("{\"csp-report\":{\"violated-directive\":\"img-src\",\"blocked-uri\":\"inline\"}}"))
        .andExpect(status().isNoContent());
  }
}
