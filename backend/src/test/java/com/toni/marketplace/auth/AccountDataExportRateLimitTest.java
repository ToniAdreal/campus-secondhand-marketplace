package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate-limit behavior of {@code GET /api/users/me/export} through the
 * {@code AuthRateLimitFilter} — the export surface's own per-<em>user</em>
 * bucket (production default 5/minute; pinned to 3/minute here so the
 * burst arithmetic stays small), keyed on the JWT principal id like the
 * message/report buckets. The export is a GET, so this is also the pin
 * that the filter's one non-POST surface is actually throttled.
 *
 * <p>The application {@code Clock} bean is replaced with a
 * {@link MutableClock} so refill timing is deterministic. Buckets are
 * in-memory and survive across tests within this class, so each test
 * uses a fresh user (per-user keys), mirroring
 * {@code ReportRateLimitTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the export surface, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.data-export-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the
    // export-bucket assertions.
    "app.auth.login-lockout.enabled=false"
})
class AccountDataExportRateLimitTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private MutableClock clock;

  private User freshExporter() {
    String tag = "expburst-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private void exportOnce(User asUser) throws Exception {
    String token = jwt.createTokenPair(asUser).accessToken();
    // Backlog #188: each successful export first mints its single-use
    // step-up grant (the confirm route is not export-bucketed; the
    // bucket arithmetic below is about the GET alone).
    String confirmBody = mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/users/me/export/confirm")
                .header("Authorization", "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String grant = new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(confirmBody).path("data").path("grant").asText();
    mockMvc.perform(get("/api/users/me/export")
            .header("Authorization", "Bearer " + token)
            .header(AccountDataExportController.EXPORT_GRANT_HEADER, grant))
        .andExpect(status().isOk());
  }

  private void exportOverLimit(User asUser) throws Exception {
    mockMvc.perform(get("/api/users/me/export")
            .header("Authorization", "Bearer " + jwt.createTokenPair(asUser).accessToken()))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void exportBurstUnderTheLimitPasses() throws Exception {
    User exporter = freshExporter();
    for (int i = 0; i < 3; i++) {
      exportOnce(exporter);
    }
  }

  @Test
  void fourthExportIsRejectedWith429Envelope() throws Exception {
    User exporter = freshExporter();
    for (int i = 0; i < 3; i++) {
      exportOnce(exporter);
    }
    // The bucket is empty; the security chain is never reached.
    mockMvc.perform(get("/api/users/me/export")
            .header("Authorization", "Bearer " + jwt.createTokenPair(exporter).accessToken()))
        .andExpect(status().isTooManyRequests())
        // 3/min = one token per 20 s.
        .andExpect(header().string("Retry-After", "20"))
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void otherUserIsUnaffectedByAnExportSpammer() throws Exception {
    User spammer = freshExporter();
    for (int i = 0; i < 3; i++) {
      exportOnce(spammer);
    }
    exportOverLimit(spammer);
    // A different principal has a full bucket of their own.
    exportOnce(freshExporter());
  }

  @Test
  void exportBucketRefillsWithTheControllableClock() throws Exception {
    User exporter = freshExporter();
    for (int i = 0; i < 3; i++) {
      exportOnce(exporter);
    }
    exportOverLimit(exporter);

    clock.advance(Duration.ofSeconds(20));
    // One token refilled (3/min → 20 s per token): exactly one more
    // export passes …
    exportOnce(exporter);
    // … and the bucket is empty again.
    exportOverLimit(exporter);
  }
}
