package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLog;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.audit.AuditTargetType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backlog #110 — the password-reset confirm joins the audit trail. The
 * attribution decision (documented on {@code PasswordResetService}):
 * actor = target = the account the reset token resolved to. A successful
 * confirm writes exactly one {@code PASSWORD_RESET_COMPLETED} row; every
 * failure mode (unknown / used / expired token, weak password) writes
 * none. The mail seam is a capturing sender and the clock a
 * {@link MutableClock}, mirroring {@link PasswordResetTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users, tokens and audit rows it creates
class PasswordResetAuditTest {

  /** Captures the raw token per user id, standing in for the mailbox. */
  static final Map<Long, String> MAILBOX = new ConcurrentHashMap<>();

  @TestConfiguration
  static class CapturingMail {
    @Bean
    @Primary
    PasswordResetMailSender capturingSender() {
      return (user, rawToken) -> MAILBOX.put(user.getId(), rawToken);
    }

    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final AtomicInteger IP_SEQ = new AtomicInteger();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private MutableClock clock;

  @BeforeEach
  void clearMailbox() {
    MAILBOX.clear();
  }

  /** Distinct client IPs per call keep the per-IP rate-limit buckets out of the way. */
  private static RequestPostProcessor uniqueIp() {
    return request -> {
      request.setRemoteAddr("10.110.0." + IP_SEQ.incrementAndGet());
      return request;
    };
  }

  private long register(String username) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/register")
            .with(uniqueIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.com\",\"password\":\"s3cret-pass1\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return ((Number) JsonPath.read(result.getResponse().getContentAsString(),
        "$.data.user.id")).longValue();
  }

  private String requestResetAndCapture(String identifier) throws Exception {
    mockMvc.perform(post("/api/auth/password-reset")
            .with(uniqueIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + identifier + "\"}"))
        .andExpect(status().isOk());
    assertThat(MAILBOX).hasSize(1);
    return MAILBOX.values().iterator().next();
  }

  private void confirm(String token, String newPassword, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .with(uniqueIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}"))
        .andExpect(status().is(expectedStatus));
  }

  private List<AuditLog> rowsFor(long userId) {
    return auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(AuditTargetType.USER, userId);
  }

  @Test
  void successfulConfirmWritesExactlyOneRowWithAccountAsActorAndTarget() throws Exception {
    long userId = register("reset-audit-alice");
    String token = requestResetAndCapture("reset-audit-alice");

    confirm(token, "n3w-s3cret12", 200);

    List<AuditLog> rows = rowsFor(userId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.PASSWORD_RESET_COMPLETED);
    // Token-holder attribution: the account the token resolved to is both
    // the actor and the target — there is no authenticated principal here.
    assertThat(rows.get(0).getActorUserId()).isEqualTo(userId);
    assertThat(rows.get(0).getCreatedAt()).isNotNull();
  }

  @Test
  void unknownTokenWritesNoRow() throws Exception {
    long userId = register("reset-audit-bob");

    confirm("totally-unknown-token", "n3w-s3cret12", 401);

    // Scoped to this test's user: the shared test DB legitimately holds
    // committed audit rows from other (non-@Transactional) test classes,
    // so a global count assertion would be order-dependent.
    assertThat(rowsFor(userId)).isEmpty();
  }

  @Test
  void replayedTokenWritesNoSecondRow() throws Exception {
    long userId = register("reset-audit-carol");
    String token = requestResetAndCapture("reset-audit-carol");

    confirm(token, "n3w-s3cret12", 200);
    // Replay of the consumed token: identical 401, and no second row.
    confirm(token, "an0ther-one", 401);

    assertThat(rowsFor(userId)).hasSize(1);
  }

  @Test
  void expiredTokenWritesNoRow() throws Exception {
    long userId = register("reset-audit-dave");
    String token = requestResetAndCapture("reset-audit-dave");

    clock.advance(Duration.ofMinutes(31));

    confirm(token, "n3w-s3cret12", 401);

    assertThat(rowsFor(userId)).isEmpty();
  }

  @Test
  void weakPasswordWritesNoRowAndLeavesTheTokenUsable() throws Exception {
    long userId = register("reset-audit-erin");
    String token = requestResetAndCapture("reset-audit-erin");

    confirm(token, "abcdefgh", 400);
    assertThat(rowsFor(userId)).isEmpty();

    // The token was not consumed by the rejected attempt: the retry
    // succeeds and writes the single row.
    confirm(token, "n3w-s3cret12", 200);
    assertThat(rowsFor(userId)).hasSize(1);
  }
}
