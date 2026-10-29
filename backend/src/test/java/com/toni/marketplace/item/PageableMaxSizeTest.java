package com.toni.marketplace.item;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.notification.Notification;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.notification.NotificationType;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Page-size clamp (backlog #75, tightened by backlog #267):
 * {@code spring.data.web.pageable.max-page-size=50} in application.yml
 * bounds {@code ?size=} on every Pageable-backed list endpoint — the
 * setting lives on Spring Data's pageable resolver, not on any one
 * controller, so {@code /api/items} and {@code /api/notifications}
 * clamp identically. An unbounded {@code ?size=500} must not run an
 * unbounded row scan, and clamping must not disturb the default size,
 * a within-cap size, the requested page, or the requested sort.
 *
 * <p>Honest scope note: #267's backlog text says no max-page-size
 * configuration existed, but #75 had already set it to 100. This round
 * tightens that existing global cap to 50 — the message-thread cap
 * (#41) — rather than introducing a cap from scratch, and the tests
 * below pin 50, including a case (#75's old cap) that now clamps.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users, items and notifications it creates
class PageableMaxSizeTest {

  private static final int SEED_COUNT = 120;
  private static final int NOTIFICATION_SEED_COUNT = 60;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private String userToken;

  @BeforeEach
  void seedSellerAndItems() {
    User seller = users.save(new User("seller-pgsize", "seller-pgsize@example.com",
        passwords.encode("seller-password")));
    userToken = jwt.createTokenPair(seller).accessToken();
    // Distinct prices (1000, 1010, …, 2190) so the sort tests can pin
    // the first and last row of a clamped page exactly.
    items.saveAll(IntStream.range(0, SEED_COUNT)
        .mapToObj(i -> new Item("Listing " + i, "description " + i,
            1000L + 10L * i, seller.getId()))
        .toList());
    notifications.saveAll(IntStream.range(0, NOTIFICATION_SEED_COUNT)
        .mapToObj(i -> new Notification(seller.getId(), NotificationType.ORDER_PAID, null))
        .toList());
  }

  @Test
  void oversizedSizeIsClampedToFifty() throws Exception {
    mockMvc.perform(get("/api/items?size=500")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        // the resolver clamped the requested size to 50 …
        .andExpect(jsonPath("$.data.size").value(50))
        .andExpect(jsonPath("$.data.content.length()").value(50))
        // … while the total is untouched
        .andExpect(jsonPath("$.data.totalElements").value(SEED_COUNT));
  }

  @Test
  void formerCapSizeIsClampedToFifty() throws Exception {
    // #75's old cap was 100; #267 tightens it, so 100 now clamps too.
    mockMvc.perform(get("/api/items?size=100")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.size").value(50))
        .andExpect(jsonPath("$.data.content.length()").value(50))
        .andExpect(jsonPath("$.data.totalElements").value(SEED_COUNT));
  }

  @Test
  void sizeWithinTheCapPassesThroughUnchanged() throws Exception {
    mockMvc.perform(get("/api/items?size=30")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.size").value(30))
        .andExpect(jsonPath("$.data.content.length()").value(30))
        .andExpect(jsonPath("$.data.totalElements").value(SEED_COUNT));
  }

  @Test
  void defaultSizeIsStillTwenty() throws Exception {
    mockMvc.perform(get("/api/items")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.content.length()").value(20))
        .andExpect(jsonPath("$.data.totalElements").value(SEED_COUNT));
  }

  @Test
  void pageNumberSurvivesClamping() throws Exception {
    mockMvc.perform(get("/api/items?page=1&size=500")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.number").value(1))
        .andExpect(jsonPath("$.data.size").value(50))
        .andExpect(jsonPath("$.data.content.length()").value(50))
        .andExpect(jsonPath("$.data.totalElements").value(SEED_COUNT));
  }

  @Test
  void sortDescSurvivesClamping() throws Exception {
    mockMvc.perform(get("/api/items?size=500&sort=price-desc")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.size").value(50))
        .andExpect(jsonPath("$.data.content.length()").value(50))
        // highest price first, 50th row is 49 steps down the ladder
        .andExpect(jsonPath("$.data.content[0].priceCents").value(2190))
        .andExpect(jsonPath("$.data.content[49].priceCents").value(1700));
  }

  @Test
  void sortAscSurvivesClamping() throws Exception {
    mockMvc.perform(get("/api/items?size=500&sort=price-asc")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.size").value(50))
        .andExpect(jsonPath("$.data.content[0].priceCents").value(1000))
        .andExpect(jsonPath("$.data.content[49].priceCents").value(1490));
  }

  @Test
  void capIsGlobalNotItemsOnly() throws Exception {
    // A second Pageable-backed list endpoint clamps identically: the
    // cap comes from the resolver, not from ItemController.
    mockMvc.perform(get("/api/notifications?size=500")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.size").value(50))
        .andExpect(jsonPath("$.data.content.length()").value(50))
        .andExpect(jsonPath("$.data.totalElements").value(NOTIFICATION_SEED_COUNT));
  }
}
