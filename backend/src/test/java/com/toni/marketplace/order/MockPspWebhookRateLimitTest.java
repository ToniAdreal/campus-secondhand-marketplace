package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Rate-limit behavior of the public mock-PSP webhook receiver through
 * {@code AuthRateLimitFilter} (backlog #306): {@code POST
 * /api/payments/webhooks/mock-psp} has its own per-<em>IP</em> bucket
 * (production default 60/minute — pinned to 3/minute here so the burst
 * arithmetic stays small), keyed on the trusted-proxy-aware client IP
 * like the CSP (#204) and telemetry (#223) buckets, never reset on
 * success. The filter runs before signature verification, so the
 * bucket keys on IP only: an unsigned flood is stopped before it
 * reaches the signature check, and a throttled delivery writes no
 * ledger row. Deliberately separate from the #305 ADMIN replay bucket
 * (an authenticated ADMIN action on a stored event, not an inbound
 * PSP delivery).
 *
 * <p>Deliberately NOT {@code @Transactional}, like
 * {@code PaymentWebhookTest}: the receiver works through
 * {@code WebhookEventRecorder}'s REQUIRES_NEW transactions, so ledger
 * rows commit for real and are cleaned explicitly. Buckets are
 * in-memory and survive across tests within this class, so each test
 * uses a fresh client IP, mirroring {@code CspReportRateLimitTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the public webhook surface, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.mock-psp-webhook-attempts-per-minute=3"})
class MockPspWebhookRateLimitTest {

  private static final AtomicInteger IP_SEQ = new AtomicInteger(1);
  private static final AtomicLong EVT_SEQ = new AtomicLong();
  private static final String SECRET = WebhookSecretStartupCheck.DEV_PLACEHOLDER_SECRET;
  private static final String URL = "/api/payments/webhooks/mock-psp";

  @Autowired private MockMvc mockMvc;
  @Autowired private WebhookEventRepository events;
  @Autowired private WebhookProperties properties;

  @BeforeEach
  void cleanBefore() {
    assertThat(properties.getWebhookSecret()).isEqualTo(SECRET);
    events.deleteAll();
  }

  @AfterEach
  void cleanAfter() {
    events.deleteAll();
  }

  private static RequestPostProcessor fromIp(String ip) {
    return request -> {
      request.setRemoteAddr(ip);
      return request;
    };
  }

  private static String freshIp() {
    return "10.206.0." + IP_SEQ.getAndIncrement();
  }

  /** A mismatched delivery: unknown capture id, so it answers 200 and records a row. */
  private static String mismatchedBody() {
    return "{\"id\":\"evt_rl_" + EVT_SEQ.incrementAndGet()
        + "\",\"type\":\"capture.succeeded\",\"captureId\":\"cap_mock_rl_unknown\"}";
  }

  private static String sign(String body) {
    return PaymentWebhookService.sign(SECRET, Instant.now().getEpochSecond(),
        body.getBytes(StandardCharsets.UTF_8));
  }

  private static String signWithWrongSecret(String body) {
    return PaymentWebhookService.sign("wrong-secret-for-rate-limit-test",
        Instant.now().getEpochSecond(), body.getBytes(StandardCharsets.UTF_8));
  }

  private ResultActions deliver(String ip, String body, String signature) throws Exception {
    var req = post(URL).with(fromIp(ip))
        .contentType(MediaType.APPLICATION_JSON).content(body);
    if (signature != null) {
      req = req.header(PaymentWebhookService.SIGNATURE_HEADER, signature);
    }
    return mockMvc.perform(req);
  }

  @Test
  void legitimateSignedBurstUnderLimitPasses() throws Exception {
    String ip = freshIp();
    for (int i = 0; i < 3; i++) {
      String body = mismatchedBody();
      deliver(ip, body, sign(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.outcome").value("MISMATCH"));
    }
  }

  @Test
  void burstOverLimitIs429WithEnvelopeAndWritesNoRow() throws Exception {
    String ip = freshIp();
    for (int i = 0; i < 3; i++) {
      String body = mismatchedBody();
      deliver(ip, body, sign(body)).andExpect(status().isOk());
    }
    String throttledBody = mismatchedBody();
    String throttledEventId = throttledBody.split("\"")[3];
    deliver(ip, throttledBody, sign(throttledBody))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(jsonPath("$.data").doesNotExist());
    // Throttled before the controller: no ledger row for the refused delivery.
    assertThat(events.findByEventId(throttledEventId)).isEmpty();
  }

  @Test
  void throttlingIsPerIp() throws Exception {
    String ip = freshIp();
    String otherIp = freshIp();
    for (int i = 0; i < 3; i++) {
      String body = mismatchedBody();
      deliver(ip, body, sign(body)).andExpect(status().isOk());
    }
    String throttledBody = mismatchedBody();
    deliver(ip, throttledBody, sign(throttledBody))
        .andExpect(status().isTooManyRequests());
    // A different IP still has its own full bucket.
    String body = mismatchedBody();
    deliver(otherIp, body, sign(body)).andExpect(status().isOk());
  }

  @Test
  void invalidSignatureStillAnswers401BelowLimit() throws Exception {
    String ip = freshIp();
    for (int i = 0; i < 2; i++) {
      String body = mismatchedBody();
      deliver(ip, body, signWithWrongSecret(body))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value(401));
    }
    // Below the ceiling an unsigned delivery is still judged on its
    // signature, not throttled: a correctly signed delivery passes.
    String body = mismatchedBody();
    deliver(ip, body, sign(body)).andExpect(status().isOk());
  }

  @Test
  void unsignedFloodIsThrottledBeforeSignatureCheck() throws Exception {
    String ip = freshIp();
    for (int i = 0; i < 3; i++) {
      String body = mismatchedBody();
      deliver(ip, body, signWithWrongSecret(body))
          .andExpect(status().isUnauthorized());
    }
    // The bucket is empty: the fourth unsigned delivery is throttled
    // before the controller ever sees it, so it answers 429, not 401.
    String body = mismatchedBody();
    deliver(ip, body, signWithWrongSecret(body))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void webhookBucketExhaustionLeavesCspBucketIntact() throws Exception {
    String ip = freshIp();
    for (int i = 0; i < 3; i++) {
      String body = mismatchedBody();
      deliver(ip, body, sign(body)).andExpect(status().isOk());
    }
    String throttledBody = mismatchedBody();
    deliver(ip, throttledBody, sign(throttledBody))
        .andExpect(status().isTooManyRequests());
    // The #204 CSP bucket is untouched: a report from the same IP still
    // reaches the intake and answers 204.
    mockMvc.perform(post("/api/security/csp-report").with(fromIp(ip))
            .contentType("application/csp-report")
            .content("{\"csp-report\":{\"violated-directive\":\"img-src\","
                + "\"blocked-uri\":\"inline\"}}"))
        .andExpect(status().isNoContent());
  }
}
