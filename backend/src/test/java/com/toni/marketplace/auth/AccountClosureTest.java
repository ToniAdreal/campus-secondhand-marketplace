package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.order.Order;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderStatus;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * DELETE /api/auth/me end-to-end via MockMvc (backlog #158), in the shared
 * test profile (email-verification gate off, so registration issues a
 * session directly).
 *
 * <p>Covered: the happy path anonymizes the user row to the
 * {@code deleted-<id>} tombstone (original username/email freed and
 * re-registerable), replaces the password hash with an unusable random
 * one, kills every session (old Bearer 401s, old refresh cookie 401s,
 * the response clears the cookie), answers the identical 401 to a later
 * login, withdraws only the caller's AVAILABLE listings (RESERVED/SOLD
 * untouched) and leaves order history intact, and writes exactly one
 * ACCOUNT_CLOSED audit row. A wrong current password is the identical
 * 401 with NOTHING changed; an anonymous call is 401 (the
 * SecurityConfig matcher — the #127 fall-through class). For a
 * TOTP-enabled account the second factor is required as well: no code
 * and a wrong code are both the identical 401 with nothing changed, and
 * a valid TOTP code closes the account (secret cleared, recovery set
 * deleted).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users, items, orders and audit rows it creates
class AccountClosureTest {

  private static final String PASSWORD = "s3cret-pass1";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private TotpRecoveryCodeRepository recoveryCodes;

  @Autowired
  private TotpService totp;

  @Autowired
  private PasswordService passwords;

  private record Session(long userId, String accessToken, Cookie refreshCookie) {}

  private Session register(String username, String email) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + email
                + "\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    long userId = ((Number) JsonPath.read(body, "$.data.user.id")).longValue();
    String access = JsonPath.read(body, "$.data.accessToken");
    Cookie refresh = result.getResponse().getCookie("refresh_token");
    assertThat(refresh).isNotNull();
    return new Session(userId, access, refresh);
  }

  private MvcResult close(String accessToken, String json, int expected) throws Exception {
    return mockMvc.perform(delete("/api/auth/me")
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json))
        .andExpect(status().is(expected))
        .andReturn();
  }

  @Test
  void happyPathAnonymizesWithdrawsListingsAndKillsEverySession() throws Exception {
    Session seller = register("closer", "closer@example.com");
    Session buyer = register("closer-buyer", "closer-buyer@example.com");
    long sellerId = seller.userId();
    long versionBefore = users.findById(sellerId).orElseThrow().getTokenVersion();
    String hashBefore = users.findById(sellerId).orElseThrow().getPasswordHash();

    Item available = items.save(new Item("Desk", "oak desk", 10000L, sellerId));
    Item reserved = items.save(new Item("Bike", "city bike", 20000L, sellerId));
    Item sold = items.save(new Item("Lamp", "desk lamp", 3000L, sellerId));
    sold.setStatus(ItemStatus.SOLD);
    sold = items.save(sold);

    // A real PAID order on the second listing (item flips to RESERVED).
    MvcResult created = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + buyer.accessToken())
            .header("Idempotency-Key", "closure-order-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + reserved.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn();
    long orderId = ((Number) JsonPath.read(
        created.getResponse().getContentAsString(), "$.data.id")).longValue();
    mockMvc.perform(post("/api/orders/" + orderId + "/pay")
            .header("Authorization", "Bearer " + buyer.accessToken()))
        .andExpect(status().isOk());
    assertThat(items.findById(reserved.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.RESERVED);

    MvcResult closed = close(seller.accessToken(),
        "{\"currentPassword\":\"" + PASSWORD + "\"}", 200);
    Cookie cleared = closed.getResponse().getCookie("refresh_token");
    assertThat(cleared).isNotNull();
    assertThat(cleared.getMaxAge()).isZero();

    // The row is a tombstone: originals freed, hash unusable, disabled,
    // token version bumped.
    User user = users.findById(sellerId).orElseThrow();
    assertThat(user.getUsername()).isEqualTo("deleted-" + sellerId);
    assertThat(user.getEmail()).isEqualTo("deleted-" + sellerId + "@deleted.invalid");
    assertThat(user.getPasswordHash()).isNotEqualTo(hashBefore);
    assertThat(passwords.matches(PASSWORD, user.getPasswordHash())).isFalse();
    assertThat(user.isDisabled()).isTrue();
    assertThat(user.getTokenVersion()).isEqualTo(versionBefore + 1);
    assertThat(user.getTotpSecret()).isNull();
    assertThat(user.isTotpEnabled()).isFalse();

    // Listings: only the AVAILABLE one was withdrawn; order history intact.
    assertThat(items.findById(available.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.WITHDRAWN);
    assertThat(items.findById(reserved.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.RESERVED);
    assertThat(items.findById(sold.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.SOLD);
    Order order = orders.findById(orderId).orElseThrow();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(order.getItem().getSellerId()).isEqualTo(sellerId);
    assertThat(order.getBuyerId()).isEqualTo(buyer.userId());

    // Every pre-close session is dead.
    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + seller.accessToken()))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/auth/refresh").cookie(seller.refreshCookie()))
        .andExpect(status().isUnauthorized());

    // Later logins with the original credentials are the identical 401.
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"closer\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"closer@example.com\",\"password\":\""
                + PASSWORD + "\"}"))
        .andExpect(status().isUnauthorized());

    // Exactly one audit row, actor = target = the account holder.
    assertThat(auditLog.findByActorUserIdOrderByIdAsc(sellerId))
        .extracting(row -> row.getAction())
        .containsExactly(AuditAction.ACCOUNT_CLOSED);

    // The freed username and email can be registered again as a new account.
    Session again = register("closer", "closer@example.com");
    assertThat(again.userId()).isNotEqualTo(sellerId);
  }

  @Test
  void wrongCurrentPasswordIs401AndChangesNothing() throws Exception {
    Session user = register("stayer", "stayer@example.com");
    Item item = items.save(new Item("Chair", "wooden chair", 5000L, user.userId()));

    close(user.accessToken(), "{\"currentPassword\":\"wr0ng-password\"}", 401);

    User row = users.findById(user.userId()).orElseThrow();
    assertThat(row.getUsername()).isEqualTo("stayer");
    assertThat(row.getEmail()).isEqualTo("stayer@example.com");
    assertThat(row.isDisabled()).isFalse();
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.AVAILABLE);
    assertThat(auditLog.findByActorUserIdOrderByIdAsc(user.userId())).isEmpty();
    // The session is untouched — the same Bearer still works.
    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + user.accessToken()))
        .andExpect(status().isOk());
  }

  @Test
  void anonymousCallerIs401() throws Exception {
    mockMvc.perform(delete("/api/auth/me")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void totpEnabledAccountNeedsItsSecondFactorToClose() throws Exception {
    Session session = register("guarded", "guarded@example.com");
    long userId = session.userId();
    MvcResult setup = mockMvc.perform(post("/api/auth/2fa/setup")
            .header("Authorization", "Bearer " + session.accessToken()))
        .andExpect(status().isOk())
        .andReturn();
    String secret = JsonPath.read(setup.getResponse().getContentAsString(), "$.data.secret");
    mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + session.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + totp.codeAt(secret, Instant.now()) + "\"}"))
        .andExpect(status().isOk());
    assertThat(recoveryCodes.countByUserIdAndUsedAtIsNull(userId)).isEqualTo(10);

    // No code at all: the identical 401, nothing changed.
    close(session.accessToken(), "{\"currentPassword\":\"" + PASSWORD + "\"}", 401);
    // A wrong code: the identical 401, nothing changed.
    close(session.accessToken(),
        "{\"currentPassword\":\"" + PASSWORD + "\",\"code\":\"000000\"}", 401);
    User row = users.findById(userId).orElseThrow();
    assertThat(row.getUsername()).isEqualTo("guarded");
    assertThat(row.isTotpEnabled()).isTrue();
    assertThat(auditLog.findByActorUserIdOrderByIdAsc(userId))
        .extracting(r -> r.getAction())
        .doesNotContain(AuditAction.ACCOUNT_CLOSED);

    // The valid TOTP code closes the account and wipes the 2FA material.
    close(session.accessToken(),
        "{\"currentPassword\":\"" + PASSWORD + "\",\"code\":\""
            + totp.codeAt(secret, Instant.now()) + "\"}", 200);
    User closed = users.findById(userId).orElseThrow();
    assertThat(closed.getUsername()).isEqualTo("deleted-" + userId);
    assertThat(closed.getTotpSecret()).isNull();
    assertThat(closed.isTotpEnabled()).isFalse();
    assertThat(recoveryCodes.countByUserIdAndUsedAtIsNull(userId)).isZero();
    assertThat(auditLog.findByActorUserIdOrderByIdAsc(userId))
        .extracting(r -> r.getAction())
        .contains(AuditAction.ACCOUNT_CLOSED);
  }
}
