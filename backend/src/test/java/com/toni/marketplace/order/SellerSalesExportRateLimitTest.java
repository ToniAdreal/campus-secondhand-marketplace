package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.auth.AccountDataExportController;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate-limit behavior of {@code GET /api/seller/sales/export.csv}
 * through {@code AuthRateLimitFilter} (backlog #317): the seller
 * sales CSV export previously had no bucket at all, while the
 * #172 account-data export did. The export scans the seller's
 * order rows, so it now has its own per-<em>user</em> bucket
 * (production default 5/minute, like the #172 download bucket —
 * pinned to 3/minute here so the burst arithmetic stays small),
 * keyed on the JWT principal id like the message / report
 * buckets, with the per-IP fallback for a missing/invalid token,
 * never reset on success — a completed export must not refill
 * the allowance, and a throttled export streams nothing.
 *
 * <p>Buckets are in-memory and survive across tests within this
 * class, so each test uses fresh users (per-user keys), mirroring
 * {@code AccountDataExportRateLimitTest}. A seller with no sales
 * still gets a 200 header-only CSV, which is the legitimate
 * export the burst tests repeat — no order flow (and its own
 * buckets) is needed to exhaust this one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the seller-export surface, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.seller-sales-export-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the bucket
    // assertions.
    "app.auth.login-lockout.enabled=false"})
class SellerSalesExportRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  private User freshSeller() {
    String tag = "sellerexp-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private static RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  private void exportOnce(User seller) throws Exception {
    mockMvc.perform(get("/api/seller/sales/export.csv")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/csv"))
        .andExpect(content().string(SellerSalesCsv.HEADER + "\r\n"));
  }

  private void exportOverLimit(User seller) throws Exception {
    mockMvc.perform(get("/api/seller/sales/export.csv")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  /** One #172 account-data export, via its step-up grant flow. */
  private void accountExportOnce(User user) throws Exception {
    String confirmBody = mockMvc.perform(
            post("/api/users/me/export/confirm")
                .header("Authorization", "Bearer " + token(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String grant = new ObjectMapper()
        .readTree(confirmBody).path("data").path("grant").asText();
    mockMvc.perform(get("/api/users/me/export")
            .header("Authorization", "Bearer " + token(user))
            .header(AccountDataExportController.EXPORT_GRANT_HEADER, grant))
        .andExpect(status().isOk());
  }

  @Test
  void singleLegitimateExportPassesWithCsvBody() throws Exception {
    exportOnce(freshSeller());
  }

  @Test
  void exportBurstIs429WithEnvelopeAndRetryAfter() throws Exception {
    User seller = freshSeller();
    for (int i = 0; i < 3; i++) {
      exportOnce(seller);
    }
    // The bucket is empty; the security chain is never reached.
    mockMvc.perform(get("/api/seller/sales/export.csv")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isTooManyRequests())
        // 3/min = one token per 20 s.
        .andExpect(header().string("Retry-After", "20"))
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void exportBucketIsPerUser() throws Exception {
    User spammer = freshSeller();
    for (int i = 0; i < 3; i++) {
      exportOnce(spammer);
    }
    exportOverLimit(spammer);
    // A different principal has a full bucket of their own.
    exportOnce(freshSeller());
  }

  @Test
  void exportBurstLeavesUserExportAndSellerOrdersListIntact() throws Exception {
    User seller = freshSeller();
    for (int i = 0; i < 3; i++) {
      exportOnce(seller);
    }
    exportOverLimit(seller);

    // The #172 account-data-export bucket is untouched: the same
    // seller can still pull their own data document.
    accountExportOnce(seller);
    // The seller-orders list GET is an unthrottled read, not an export.
    mockMvc.perform(get("/api/seller/orders")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    // And the seller-export bucket is still empty afterwards.
    exportOverLimit(seller);
  }

  @Test
  void anonymousExportFallbackStillThrottles() throws Exception {
    // No token: the filter falls back to a per-IP bucket. The first three
    // anonymous exports are answered 401 by the security chain (the
    // bucket still consumed each attempt); the fourth is short-circuited
    // 429 by the filter itself. A dedicated remote address keeps this
    // test's IP bucket away from every other test's.
    String anonymousIp = "203.0.113.92";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(get("/api/seller/sales/export.csv")
              .with(fromIp(anonymousIp)))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(get("/api/seller/sales/export.csv")
            .with(fromIp(anonymousIp)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"));
  }
}
