package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.List;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-address mail bucket (backlog #354) through the
 * {@link AuthRateLimitFilter}: {@code POST /api/auth/password-reset},
 * {@code POST /api/auth/email-verification/resend} and {@code POST
 * /api/auth/email-change} share ONE limiter keyed on the SHA-256 hash of
 * the trimmed, lowercased target address (5 mails per address per hour
 * here — the production default), IN ADDITION to their own per-IP buckets
 * (100/minute here, so the per-IP ceiling never trips in this class).
 * Each test uses a fresh client IP and a distinct victim address because
 * the limiter buckets are in-memory and survive across tests within this
 * class; the register/login setup for the email-change test runs on the
 * test's IP too, and the successful login resets the credential bucket,
 * so the setup never starves the buckets under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.password-reset-attempts-per-minute=100",
    "app.auth.rate-limit.email-verification-attempts-per-minute=100",
    "app.auth.rate-limit.email-change-request-attempts-per-minute=100",
    "app.auth.rate-limit.mail-per-address-attempts-per-window=5",
    "app.auth.rate-limit.mail-per-address-window=PT1H",
    // Keep the per-account lockout out of this class (it is not what is
    // being asserted, and the flows below never fail a login anyway).
    "app.auth.login-lockout.enabled=false"
})
class MailPerAddressRateLimitTest {

  private static final AtomicInteger IP_SEQ = new AtomicInteger(400);
  private static final String PASSWORD = "s3cret-pass1";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private MeterRegistry registry;

  private String ip;
  private Logger rootLogger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    ip = "10.204.0." + IP_SEQ.getAndIncrement();
    rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    appender = new ListAppender<>();
    appender.start();
    rootLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(appender);
  }

  private RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  private void requestReset(String address) throws Exception {
    mockMvc.perform(post("/api/auth/password-reset")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + address + "\"}"))
        .andExpect(status().isOk());
  }

  private void resend(String address) throws Exception {
    mockMvc.perform(post("/api/auth/email-verification/resend")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + address + "\"}"))
        .andExpect(status().isOk());
  }

  private String registerAndLogin(String username, String email) throws Exception {
    mockMvc.perform(post("/api/auth/register")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + email
                + "\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk());
    MvcResult loggedIn = mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(loggedIn.getResponse().getContentAsString(), "$.data.accessToken");
  }

  private void requestEmailChange(String accessToken, String newEmail) throws Exception {
    mockMvc.perform(post("/api/auth/email-change")
            .with(fromIp(ip))
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newEmail\":\"" + newEmail + "\"}"))
        .andExpect(status().isOk());
  }

  private String allLoggedMessages() {
    StringBuilder out = new StringBuilder();
    for (ILoggingEvent event : appender.list) {
      out.append(event.getFormattedMessage()).append('\n');
    }
    return out.toString();
  }

  @Test
  void sixthResetForSameAddressIs429WhileDifferentAddressPasses() throws Exception {
    String victim = "victim-354a@example.com";
    for (int i = 0; i < 5; i++) {
      requestReset(victim);
    }
    mockMvc.perform(post("/api/auth/password-reset")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + victim + "\"}"))
        .andExpect(status().isTooManyRequests())
        // 5/hour = one token per 12 minutes.
        .andExpect(header().string("Retry-After", "720"))
        .andExpect(header().string("RateLimit-Limit", "5"))
        .andExpect(header().string("RateLimit-Remaining", "0"))
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(jsonPath("$.data").doesNotExist());
    // A different address from the SAME IP still passes: the per-IP
    // bucket (100/min here) never tripped, and the address bucket is
    // per-address — the flood against one mailbox starves nobody else.
    requestReset("other-354a@example.com");
  }

  @Test
  void normalizedCaseVariantsShareOneBucket() throws Exception {
    requestReset("Case-354b@Example.com");
    requestReset("Case-354b@Example.com");
    requestReset("case-354b@example.com");
    requestReset("case-354b@example.com");
    requestReset("CASE-354B@EXAMPLE.COM");
    mockMvc.perform(post("/api/auth/password-reset")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"cAsE-354b@example.com\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void resendSharesTheSamePerAddressBucket() throws Exception {
    String victim = "victim-354c@example.com";
    for (int i = 0; i < 5; i++) {
      requestReset(victim);
    }
    // The resend endpoint draws from the SAME address bucket the
    // resets filled: switching endpoints does not buy a fresh budget.
    mockMvc.perform(post("/api/auth/email-verification/resend")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + victim + "\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
    // A different address still passes on the resend endpoint.
    resend("other-354c@example.com");
  }

  @Test
  void emailChangeRequestSharesTheSamePerAddressBucket() throws Exception {
    String access = registerAndLogin("changer354", "changer354@example.com");
    String victim = "victim-354d@example.com";
    for (int i = 0; i < 5; i++) {
      requestReset(victim);
    }
    // The email-change request mails its token to the NEW address, so
    // it draws from the same bucket — exhausting it via resets leaves
    // the change request throttled too.
    mockMvc.perform(post("/api/auth/email-change")
            .with(fromIp(ip))
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newEmail\":\"" + victim + "\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
    // A different new address still stages fine.
    requestEmailChange(access, "other-354d@example.com");
  }

  @Test
  void confirmEndpointsAreNotPerAddressThrottled() throws Exception {
    String victim = "victim-354e@example.com";
    for (int i = 0; i < 5; i++) {
      requestReset(victim);
    }
    // The address bucket is exhausted, but the confirm endpoints carry
    // no address to key on: a bogus reset token still reaches the
    // controller and answers its usual 401, not a 429.
    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"bogus\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    // Same for the verification confirm (identical 400 for a bogus token).
    mockMvc.perform(post("/api/auth/email-verification/confirm")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"bogus\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void unparseableAndOversizedBodiesShareTheUnknownBucket() throws Exception {
    // A body with no usable address field AND a body over the filter's
    // 8 KB read cap both key on the shared email:unknown bucket:
    // malformed traffic throttles itself instead of bypassing the
    // limit, and the two shapes share ONE budget — three malformed
    // plus two oversized fill it, and the sixth request of either
    // shape trips the same 429. The controller 400s the malformed
    // ones (validation) and answers the oversized ones normally (a
    // declared Content-Length over the cap skips the read, so the
    // controller still sees the full body while the bucket has tokens).
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/auth/password-reset")
              .with(fromIp(ip))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"))
          .andExpect(status().isBadRequest());
    }
    String victim = "victim-354g@example.com";
    String pad = "x".repeat(9 * 1024);
    String body = "{\"usernameOrEmail\":\"" + victim + "\",\"pad\":\"" + pad + "\"}";
    for (int i = 0; i < 2; i++) {
      mockMvc.perform(post("/api/auth/password-reset")
              .with(fromIp(ip))
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isOk());
    }
    mockMvc.perform(post("/api/auth/password-reset")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
    mockMvc.perform(post("/api/auth/password-reset")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void targetAddressNeverAppearsInLogsOrMetrics() throws Exception {
    String victim = "logpin-354f-9z4x7@example.com";
    for (int i = 0; i < 5; i++) {
      requestReset(victim);
    }
    // The 429 refusal itself must not log the address either, and
    // the resend for the same exhausted address 429s the same way.
    mockMvc.perform(post("/api/auth/password-reset")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + victim + "\"}"))
        .andExpect(status().isTooManyRequests());
    mockMvc.perform(post("/api/auth/email-verification/resend")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + victim + "\"}"))
        .andExpect(status().isTooManyRequests());
    String logged = allLoggedMessages();
    assertThat(logged).doesNotContain(victim);
    assertThat(logged).doesNotContain("logpin-354f-9z4x7");
    // Metrics half: no meter anywhere in the registry carries the
    // address (or its local part) as a tag value — the bucket key is
    // a hash, and nothing in the mail path labels a metric by address.
    List<String> tagged = registry.getMeters().stream()
        .flatMap(meter -> meter.getId().getTags().stream())
        .map(Tag::getValue)
        .filter(value -> value.contains("logpin-354f-9z4x7"))
        .toList();
    assertThat(tagged).isEmpty();
  }
}
