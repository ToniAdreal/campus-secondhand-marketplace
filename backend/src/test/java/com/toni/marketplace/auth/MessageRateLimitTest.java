package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate-limit behavior of {@code POST /api/messages} through the
 * {@link AuthRateLimitFilter} — the message surface's own per-<em>user</em>
 * bucket (default 30 sends/minute), keyed on the JWT principal id rather
 * than the client IP.
 *
 * <p>The application {@code Clock} bean is replaced with a {@link MutableClock}
 * so refill timing is deterministic. Buckets are in-memory and survive
 * across tests within this class, so each test uses fresh users (per-user
 * keys) and, where the anonymous IP-fallback path is exercised, a fresh
 * client IP.
 *
 * <p>All users are rolled back after each test ({@code @Transactional});
 * the in-memory limiter state is what demands the fresh-identity discipline.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users/items/messages it creates
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the message surface, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.message-attempts-per-minute=30",
    // Per-account lockout stays off so no other throttle masks the
    // message-bucket assertions.
    "app.auth.login-lockout.enabled=false"
})
class MessageRateLimitTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final AtomicLong SEQ = new AtomicLong();
  private static final AtomicLong IP_SEQ = new AtomicLong(200);

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private MutableClock clock;

  private User sender;
  private User receiver;
  private Item item;
  private String ip;

  @BeforeEach
  void setUp() {
    String tag = "msgspammer-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    sender = users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
    String tag2 = "msgreceiver-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    receiver = users.save(new User(tag2, tag2 + "@example.com", passwords.encode("password")));
    // The receiver owns the listing: since backlog #112 a send must be a
    // buyer → seller message (or a seller reply inside an existing
    // thread), so the burst below is a buyer messaging the seller, and
    // the receiver's "reply" in otherUserIsUnaffectedByASpammer is a
    // seller reply inside the thread the burst opened.
    item = items.save(new Item("Desk lamp", "IKEA Tertial", 12000L, receiver.getId()));
    ip = "10.202.0." + IP_SEQ.getAndIncrement();
  }

  private RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  private String sendBody(User recipient, String text) {
    String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
    return "{\"itemId\":" + item.getId() + ",\"receiverId\":" + recipient.getId()
        + ",\"body\":\"" + escaped + "\"}";
  }

  private void sendOnce(User asUser, User recipient, String text) throws Exception {
    mockMvc.perform(post("/api/messages")
            .with(fromIp(ip))
            .header("Authorization", "Bearer " + jwt.createTokenPair(asUser).accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(sendBody(recipient, text)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  void messageBurstUnderTheLimitPasses() throws Exception {
    for (int i = 0; i < 30; i++) {
      sendOnce(sender, receiver, "hello " + i);
    }
  }

  @Test
  void thirtyFirstMessageIsRejectedWith429Envelope() throws Exception {
    for (int i = 0; i < 30; i++) {
      sendOnce(sender, receiver, "hello " + i);
    }
    // The bucket is empty; the security chain is never reached.
    mockMvc.perform(post("/api/messages")
            .with(fromIp(ip))
            .header("Authorization", "Bearer " + jwt.createTokenPair(sender).accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(sendBody(receiver, "one too many")))
        .andExpect(status().isTooManyRequests())
        // 30/min = one token per 2 s.
        .andExpect(header().string("Retry-After", "2"))
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void otherUserIsUnaffectedByASpammer() throws Exception {
    for (int i = 0; i < 30; i++) {
      sendOnce(sender, receiver, "spam " + i);
    }
    // The receiver (a different principal) has a full bucket of their own,
    // even from the same client IP.
    sendOnce(receiver, sender, "reply to the spammer");
  }

  @Test
  void anonymousRequestsFallBackToAnIpBucket() throws Exception {
    // No Authorization header: the filter keys on the client IP and lets the
    // request through to the security chain, which answers 401.
    for (int i = 0; i < 30; i++) {
      mockMvc.perform(post("/api/messages")
              .with(fromIp(ip))
              .contentType(MediaType.APPLICATION_JSON)
              .content(sendBody(receiver, "no token " + i)))
          .andExpect(status().isUnauthorized());
    }
    // The 31st anonymous attempt is throttled before the security chain —
    // 429, not 401 — so anonymous hammering is still stopped.
    mockMvc.perform(post("/api/messages")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content(sendBody(receiver, "no token 30")))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "2"))
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void messageBucketRefillsWithTheControllableClock() throws Exception {
    for (int i = 0; i < 30; i++) {
      sendOnce(sender, receiver, "tick " + i);
    }
    sendOnceOverLimit();

    clock.advance(Duration.ofSeconds(2));
    // One token refilled (30/min → 2 s per token): exactly one more send
    // passes …
    sendOnce(sender, receiver, "refilled one");
    // … and the bucket is empty again.
    sendOnceOverLimit();

    clock.advance(Duration.ofMinutes(1));
    // Full refill: a fresh burst of 30 passes.
    for (int i = 0; i < 30; i++) {
      sendOnce(sender, receiver, "again " + i);
    }
  }

  private void sendOnceOverLimit() throws Exception {
    mockMvc.perform(post("/api/messages")
            .with(fromIp(ip))
            .header("Authorization", "Bearer " + jwt.createTokenPair(sender).accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(sendBody(receiver, "over the limit")))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }
}
