package com.toni.marketplace.moderation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.MutableClock;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate-limit behavior of {@code POST /api/items/{id}/report} through the
 * {@code AuthRateLimitFilter} — the report surface's own per-<em>user</em>
 * bucket (production default 10/minute; pinned to 3/minute here so the
 * burst arithmetic stays small), keyed on the JWT principal id like the
 * message bucket.
 *
 * <p>The application {@code Clock} bean is replaced with a
 * {@link MutableClock} so refill timing is deterministic. Buckets are
 * in-memory and survive across tests within this class, so each test
 * uses fresh users (per-user keys). Repeat reports of one listing
 * return the existing OPEN row (the #160 dedupe), but every attempt
 * still consumes a bucket token — the filter runs before the service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the report surface, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.report-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the
    // report-bucket assertions.
    "app.auth.login-lockout.enabled=false"
})
class ReportRateLimitTest {

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
  @Autowired private ItemRepository items;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private MutableClock clock;

  private User seller;
  private Item item;

  @BeforeEach
  void setUp() {
    String tag = "repspam-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    seller = users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
    item = items.save(new Item("Burst listing", "seeded", 12000L, seller.getId()));
  }

  private User freshReporter() {
    String tag = "reporter-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private void reportOnce(User asUser) throws Exception {
    mockMvc.perform(post("/api/items/{id}/report", item.getId())
            .header("Authorization", "Bearer " + jwt.createTokenPair(asUser).accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SPAM\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  private void reportOverLimit(User asUser) throws Exception {
    mockMvc.perform(post("/api/items/{id}/report", item.getId())
            .header("Authorization", "Bearer " + jwt.createTokenPair(asUser).accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SPAM\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void reportBurstUnderTheLimitPasses() throws Exception {
    User reporter = freshReporter();
    for (int i = 0; i < 3; i++) {
      reportOnce(reporter);
    }
  }

  @Test
  void fourthReportIsRejectedWith429Envelope() throws Exception {
    User reporter = freshReporter();
    for (int i = 0; i < 3; i++) {
      reportOnce(reporter);
    }
    // The bucket is empty; the security chain is never reached.
    mockMvc.perform(post("/api/items/{id}/report", item.getId())
            .header("Authorization", "Bearer " + jwt.createTokenPair(reporter).accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SPAM\"}"))
        .andExpect(status().isTooManyRequests())
        // 3/min = one token per 20 s.
        .andExpect(header().string("Retry-After", "20"))
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void otherUserIsUnaffectedByAReportSpammer() throws Exception {
    User spammer = freshReporter();
    for (int i = 0; i < 3; i++) {
      reportOnce(spammer);
    }
    reportOverLimit(spammer);
    // A different principal has a full bucket of their own.
    reportOnce(freshReporter());
  }

  @Test
  void reportBucketRefillsWithTheControllableClock() throws Exception {
    User reporter = freshReporter();
    for (int i = 0; i < 3; i++) {
      reportOnce(reporter);
    }
    reportOverLimit(reporter);

    clock.advance(Duration.ofSeconds(20));
    // One token refilled (3/min → 20 s per token): exactly one more
    // report passes …
    reportOnce(reporter);
    // … and the bucket is empty again.
    reportOverLimit(reporter);
  }
}
