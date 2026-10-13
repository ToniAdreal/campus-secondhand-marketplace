package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.Order;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The account-closure happy path that runs through a real order
 * (backlog #158), split out of {@link AccountClosureTest}.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link com.toni.marketplace.order.OrderIdempotencyTest} and
 * {@link com.toni.marketplace.notification.NotificationTest}: order
 * creation reserves its idempotency key in a {@code REQUIRES_NEW}
 * transaction whose foreign keys ({@code fk_idem_user} on the buyer,
 * {@code fk_idem_order} on the created order) can only see rows that
 * are already committed. Inside a wrapping test transaction the
 * registered users and the created order never commit, so the
 * reservation intermittently failed its FK check under full-suite
 * load and {@code POST /api/orders} answered 500 ("idempotency
 * reservation lost") — never in isolation, repeatedly in full runs.
 * Running the flow the way production runs it (every step commits in
 * its own transaction) removes the whole class of failure; the test
 * owns its data via its unique usernames and wipes every table it
 * touches in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountClosureOrderFlowTest {

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
  private PasswordService passwords;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private IdempotencyKeyRepository keys;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private TotpRecoveryCodeRepository recoveryCodes;

  @Autowired
  private PasswordResetTokenRepository resetTokens;

  @Autowired
  private EmailVerificationTokenRepository verificationTokens;

  @Autowired
  private PasswordHistoryRepository passwordHistory;

  @AfterEach
  void cleanUp() {
    notifications.deleteAll();
    keys.deleteAll();
    orders.deleteAll();
    auditLog.deleteAll();
    recoveryCodes.deleteAll();
    resetTokens.deleteAll();
    verificationTokens.deleteAll();
    passwordHistory.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

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

    MvcResult closed = mockMvc.perform(delete("/api/auth/me")
            .header("Authorization", "Bearer " + seller.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
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
    // No open session in this non-transactional test: getId() on the lazy
    // Item proxy does not initialize it, and the item row itself (asserted
    // RESERVED above) carries the seller id the original assertion checked
    // through the proxy.
    assertThat(order.getItem().getId()).isEqualTo(reserved.getId());
    assertThat(items.findById(reserved.getId()).orElseThrow().getSellerId())
        .isEqualTo(sellerId);
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
}
