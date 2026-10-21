package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
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
 * CSP violation report intake (backlog #204): POST
 * /api/security/csp-report accepts both browser report shapes without
 * any credentials, answers 204, counts {@code csp_reports_total} per
 * sanitized directive, and logs only the directive + the blocked URI's
 * origin — the full URL, its path/query, the document URI, the body
 * and the headers must never reach the log (pinned here with a logback
 * {@code ListAppender} on the {@link CspReportService} logger, the
 * {@code RefreshTheftLoggingTest} pattern). Oversize bodies answer the
 * 413 envelope, malformed/empty bodies the 400 envelope.
 *
 * <p>The shared test profile disables the auth rate limiter, so these
 * tests exercise the intake with the CSP bucket passing through; the
 * dedicated {@code CspReportRateLimitTest} below re-enables it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CspReportTest {

  private static final String LEGACY_REPORT = """
      {"csp-report":{
        "document-uri":"https://market.example/listings/1?token=doc-secret",
        "violated-directive":"script-src-elem",
        "blocked-uri":"https://evil.example/payload.js?x=blocked-secret",
        "source-file":"https://evil.example/payload.js"}}
      """;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private MeterRegistry registry;

  private Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attachAppender() {
    logger = (Logger) LoggerFactory.getLogger(CspReportService.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
  }

  private double counter(String directive) {
    Counter counter = registry.find(CspReportService.COUNTER_NAME)
        .tag("directive", directive).counter();
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
  void legacyShapeIsAcceptedCountedAndLoggedSanitized() throws Exception {
    double before = counter("script-src-elem");

    mockMvc.perform(post("/api/security/csp-report")
            .contentType("application/csp-report")
            .content(LEGACY_REPORT))
        .andExpect(status().isNoContent());

    assertThat(counter("script-src-elem")).isEqualTo(before + 1);
    String logged = allLoggedMessages();
    assertThat(logged).contains("script-src-elem");
    assertThat(logged).contains("https://evil.example");
    // The privacy pin: no path, no query, no document URI, no secret
    // from anywhere in the submitted report may appear in the log.
    assertThat(logged).doesNotContain("payload");
    assertThat(logged).doesNotContain("blocked-secret");
    assertThat(logged).doesNotContain("doc-secret");
    assertThat(logged).doesNotContain("listings");
  }

  @Test
  void reportingApiArrayCountsEachEntryPerDirective() throws Exception {
    double styleBefore = counter("style-src");
    double imgBefore = counter("img-src");
    String body = """
        [{"type":"csp-violation","url":"https://market.example/page?token=url-secret",
          "body":{"violatedDirective":"style-src",
                  "blockedURL":"https://cdn.bad.example:8443/a/b.css?q=css-secret",
                  "documentURL":"https://market.example/page"}},
         {"type":"csp-violation",
          "body":{"violatedDirective":"img-src","blockedURL":"inline"}}]
        """;

    mockMvc.perform(post("/api/security/csp-report")
            .contentType("application/reports+json")
            .content(body))
        .andExpect(status().isNoContent());

    assertThat(counter("style-src")).isEqualTo(styleBefore + 1);
    assertThat(counter("img-src")).isEqualTo(imgBefore + 1);
    String logged = allLoggedMessages();
    assertThat(logged).contains("https://cdn.bad.example:8443");
    assertThat(logged).contains("blockedOrigin=inline");
    assertThat(logged).doesNotContain("b.css");
    assertThat(logged).doesNotContain("css-secret");
    assertThat(logged).doesNotContain("url-secret");
  }

  @Test
  void oversizeBodyAnswers413Envelope() throws Exception {
    String oversize = "x".repeat(16_385);

    mockMvc.perform(post("/api/security/csp-report")
            .contentType(MediaType.APPLICATION_JSON)
            .content(oversize))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value(413))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void malformedJsonAnswers400Envelope() throws Exception {
    mockMvc.perform(post("/api/security/csp-report")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void emptyBodyAnswers400Envelope() throws Exception {
    mockMvc.perform(post("/api/security/csp-report")
            .contentType(MediaType.APPLICATION_JSON)
            .content(""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void unrecognizedShapeAndEmptyArrayAnswer400() throws Exception {
    mockMvc.perform(post("/api/security/csp-report")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"foo\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    mockMvc.perform(post("/api/security/csp-report")
            .contentType(MediaType.APPLICATION_JSON)
            .content("[]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }
}

/**
 * Unit half of backlog #204: the sanitizers are pure functions, so
 * their edge cases (userinfo, ports, opaque schemes, invented
 * directives) are pinned directly, and the service is driven with a
 * {@link SimpleMeterRegistry} without a Spring context.
 */
class CspReportServiceTest {

  @Test
  void sanitizeDirectiveKeepsKnownNamesAndBoundsTheRest() {
    assertThat(CspReportService.sanitizeDirective("script-src-elem"))
        .isEqualTo("script-src-elem");
    assertThat(CspReportService.sanitizeDirective("SCRIPT-SRC 'unsafe-inline'"))
        .isEqualTo("script-src");
    assertThat(CspReportService.sanitizeDirective("made-up-directive"))
        .isEqualTo("other");
    assertThat(CspReportService.sanitizeDirective(null)).isEqualTo("unknown");
    assertThat(CspReportService.sanitizeDirective("  ")).isEqualTo("unknown");
    assertThat(CspReportService.sanitizeDirective("!!!")).isEqualTo("unknown");
  }

  @Test
  void sanitizeBlockedUriKeepsOriginOnly() {
    assertThat(CspReportService.sanitizeBlockedUri(
        "https://evil.example:8443/path/to.js?q=1#frag"))
        .isEqualTo("https://evil.example:8443");
    // Userinfo is part of the authority, never of the origin we keep.
    assertThat(CspReportService.sanitizeBlockedUri(
        "https://user:pass@evil.example/private"))
        .isEqualTo("https://evil.example");
    assertThat(CspReportService.sanitizeBlockedUri("inline")).isEqualTo("inline");
    assertThat(CspReportService.sanitizeBlockedUri("eval")).isEqualTo("eval");
    assertThat(CspReportService.sanitizeBlockedUri("data:text/html;base64,PHg+"))
        .isEqualTo("data");
    assertThat(CspReportService.sanitizeBlockedUri("blob:https://example.com/some-id"))
        .isEqualTo("blob");
    assertThat(CspReportService.sanitizeBlockedUri(null)).isEqualTo("unknown");
    assertThat(CspReportService.sanitizeBlockedUri("/relative/path")).isEqualTo("other");
  }

  @Test
  void serviceCountsAndRejectsMalformedBodies() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    CspReportService service = new CspReportService(new ObjectMapper(), meterRegistry);

    int accepted = service.accept("""
        {"csp-report":{"violated-directive":"connect-src",
                       "blocked-uri":"https://api.bad.example/socket?key=k"}}
        """.getBytes(StandardCharsets.UTF_8));

    assertThat(accepted).isEqualTo(1);
    Counter counter = meterRegistry.find(CspReportService.COUNTER_NAME)
        .tag("directive", "connect-src").counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
    assertThatThrownBy(() -> service.accept("[]".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(CspReportService.MalformedCspReportException.class);
    assertThatThrownBy(() -> service.accept("{".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(CspReportService.MalformedCspReportException.class);
  }
}

/**
 * Rate-limit half of backlog #204: with the limiter re-enabled and the
 * CSP bucket pinned to 3/minute, the fourth report from one IP answers
 * the 429 envelope with {@code Retry-After}. Each test uses a fresh
 * client IP (the buckets are in-memory and survive across tests in
 * this class), mirroring {@code AuthRateLimitTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.csp-report-attempts-per-minute=3"
})
class CspReportRateLimitTest {

  private static final AtomicInteger IP_SEQ = new AtomicInteger(1);

  private static final String REPORT = """
      {"csp-report":{"violated-directive":"img-src","blocked-uri":"inline"}}
      """;

  @Autowired
  private MockMvc mockMvc;

  private static RequestPostProcessor fromIp(String ip) {
    return request -> {
      request.setRemoteAddr(ip);
      return request;
    };
  }

  @Test
  void fourthReportInAMinuteIsThrottledWith429Envelope() throws Exception {
    String ip = "10.204.0." + IP_SEQ.getAndIncrement();
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/security/csp-report")
              .with(fromIp(ip))
              .contentType("application/csp-report")
              .content(REPORT))
          .andExpect(status().isNoContent());
    }
    mockMvc.perform(post("/api/security/csp-report")
            .with(fromIp(ip))
            .contentType("application/csp-report")
            .content(REPORT))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"));
  }

  @Test
  void throttlingIsPerIp() throws Exception {
    String ip = "10.204.0." + IP_SEQ.getAndIncrement();
    String otherIp = "10.204.0." + IP_SEQ.getAndIncrement();
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/security/csp-report")
              .with(fromIp(ip))
              .contentType("application/csp-report")
              .content(REPORT))
          .andExpect(status().isNoContent());
    }
    // A different IP still has its own full bucket.
    mockMvc.perform(post("/api/security/csp-report")
            .with(fromIp(otherIp))
            .contentType("application/csp-report")
            .content(REPORT))
        .andExpect(status().isNoContent());
  }
}

/**
 * Header-wiring half of backlog #204: with {@code csp-enabled} AND
 * {@code csp-report-enabled} both on, the policy gains the
 * {@code report-uri} directive and responses carry
 * {@code Reporting-Endpoints}. Separate class because
 * @TestPropertySource spins a new context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.security.headers.csp-enabled=true",
    "app.security.headers.csp-report-enabled=true"
})
class CspReportHeaderWiringTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void policyGainsReportUriAndReportingEndpointsHeader() throws Exception {
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Security-Policy",
            SecurityConfig.CONTENT_SECURITY_POLICY
                + "; report-uri " + SecurityConfig.CSP_REPORT_PATH))
        .andExpect(header().string("Reporting-Endpoints",
            SecurityConfig.REPORTING_ENDPOINTS_VALUE));
  }
}

/**
 * Header-wiring off half of backlog #204: with {@code csp-enabled} on
 * but {@code csp-report-enabled} off (its default), the policy stays
 * byte-identical to the backlog #80 policy and no
 * {@code Reporting-Endpoints} header is sent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.security.headers.csp-enabled=true")
class CspReportHeaderWiringOffTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void policyIsUnchangedAndNoReportingEndpointsHeader() throws Exception {
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Security-Policy",
            SecurityConfig.CONTENT_SECURITY_POLICY))
        .andExpect(header().doesNotExist("Reporting-Endpoints"));
  }
}
