package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.message.MessageRepository;
import com.toni.marketplace.offer.PriceOfferRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.OrderRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * User-to-user blocking (backlog #187): the CRUD + idempotency matrix,
 * the three enforcement surfaces (message send with no-leak 403,
 * browse/search exclusion, offer refusal) and the
 * blocking-never-rewrites-history pins (existing threads stay
 * readable, an in-flight order is untouched).
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@code MessageTest} / {@code PriceOfferTest}: each test owns its
 * rows via unique usernames and wipes the touched tables afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserBlockTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserBlockRepository blocks;

  @Autowired
  private MessageRepository messages;

  @Autowired
  private PriceOfferRepository offers;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private IdempotencyKeyRepository keys;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @AfterEach
  void cleanUp() {
    blocks.deleteAll();
    messages.deleteAll();
    offers.deleteAll();
    keys.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser() {
    String tag = "block-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item newItem(User seller, String title) {
    return items.save(new Item(title, "a used " + title, 12000L, seller.getId()));
  }

  private void block(User blocker, long blockedId) throws Exception {
    mockMvc.perform(post("/api/users/{id}/block", blockedId)
            .header("Authorization", "Bearer " + token(blocker)))
        .andExpect(status().isOk());
  }

  // --- CRUD + idempotency ---

  @Test
  void blockListAndUnblockAreIdempotent() throws Exception {
    User alice = newUser();
    User bob = newUser();

    block(alice, bob.getId());
    // Repeat block: no-op 200, still exactly one row.
    block(alice, bob.getId());
    assertThat(blocks.findAll()).hasSize(1);

    mockMvc.perform(get("/api/users/me/blocks")
            .header("Authorization", "Bearer " + token(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].blockedId").value(bob.getId()))
        .andExpect(jsonPath("$.data[0].blockedUsername").value(bob.getUsername()));

    // Bob's own list is empty: blocks are scoped to the blocker.
    mockMvc.perform(get("/api/users/me/blocks")
            .header("Authorization", "Bearer " + token(bob)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));

    mockMvc.perform(delete("/api/users/{id}/block", bob.getId())
            .header("Authorization", "Bearer " + token(alice)))
        .andExpect(status().isOk());
    // Repeat unblock: no-op 200.
    mockMvc.perform(delete("/api/users/{id}/block", bob.getId())
            .header("Authorization", "Bearer " + token(alice)))
        .andExpect(status().isOk());
    assertThat(blocks.findAll()).isEmpty();
  }

  @Test
  void selfBlockIs422AndUnknownUserIs404() throws Exception {
    User alice = newUser();

    mockMvc.perform(post("/api/users/{id}/block", alice.getId())
            .header("Authorization", "Bearer " + token(alice)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));
    mockMvc.perform(post("/api/users/{id}/block", 999_999_999L)
            .header("Authorization", "Bearer " + token(alice)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    mockMvc.perform(delete("/api/users/{id}/block", 999_999_999L)
            .header("Authorization", "Bearer " + token(alice)))
        .andExpect(status().isNotFound());

    mockMvc.perform(post("/api/users/{id}/block", alice.getId()))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/users/me/blocks"))
        .andExpect(status().isUnauthorized());
  }

  // --- enforcement: messages ---

  @Test
  void blockedUserCannotMessageBlockerAndLearnsNothing() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, "Desk lamp");

    // The seller blocks the buyer; the buyer's send now earns the
    // participant guard's exact 403 copy — the word "block" appears
    // nowhere in the answer.
    block(seller, buyer.getId());
    mockMvc.perform(post("/api/messages")
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + ",\"receiverId\":" + seller.getId()
                + ",\"body\":\"Is this still available?\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("not a participant in this conversation"))
        .andExpect(content().string(Matchers.not(Matchers.containsString("block"))));
    assertThat(messages.findAll()).isEmpty();
  }

  @Test
  void existingThreadStaysReadableAfterBlock() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, "Desk lamp");

    mockMvc.perform(post("/api/messages")
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + ",\"receiverId\":" + seller.getId()
                + ",\"body\":\"hello\"}"))
        .andExpect(status().isOk());

    block(seller, buyer.getId());

    // History is untouched: the buyer can still read the thread.
    mockMvc.perform(get("/api/messages").param("itemId", String.valueOf(item.getId()))
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(1));
  }

  // --- enforcement: browse/search exclusion (LIKE path on H2) ---

  @Test
  void blockedSellersListingsDisappearFromBlockersBrowseAndSearch() throws Exception {
    User blocker = newUser();
    User blockedSeller = newUser();
    User otherSeller = newUser();
    Item hidden = newItem(blockedSeller, "blocked bicycle");
    Item visible = newItem(otherSeller, "visible bicycle");

    mockMvc.perform(get("/api/items")
            .header("Authorization", "Bearer " + token(blocker)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[*].id",
            Matchers.hasItems(hidden.getId().intValue(), visible.getId().intValue())));

    block(blocker, blockedSeller.getId());

    mockMvc.perform(get("/api/items")
            .header("Authorization", "Bearer " + token(blocker)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[*].id",
            Matchers.not(Matchers.hasItem(hidden.getId().intValue()))))
        .andExpect(jsonPath("$.data.content[*].id",
            Matchers.hasItem(visible.getId().intValue())));
    // Keyword search (the LIKE path on H2) excludes the same seller.
    mockMvc.perform(get("/api/items").param("q", "bicycle")
            .header("Authorization", "Bearer " + token(blocker)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[*].id",
            Matchers.not(Matchers.hasItem(hidden.getId().intValue()))));
    // A user who blocked nobody still sees the listing.
    mockMvc.perform(get("/api/items")
            .header("Authorization", "Bearer " + token(otherSeller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[*].id",
            Matchers.hasItem(hidden.getId().intValue())));
  }

  // --- enforcement: offers + in-flight orders ---

  @Test
  void blockedBuyerOfferIsRefused403() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, "Film camera");

    block(seller, buyer.getId());
    mockMvc.perform(post("/api/items/{itemId}/offers", item.getId())
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":8000}"))
        .andExpect(status().isForbidden())
        .andExpect(content().string(Matchers.not(Matchers.containsString("block"))));
    assertThat(offers.findAll()).isEmpty();
  }

  @Test
  void inFlightOrderIsUnaffectedByLaterBlock() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, "Film camera");

    String orderBody = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", "block-order-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long orderId = ((Number) com.jayway.jsonpath.JsonPath.read(orderBody, "$.data.id")).longValue();

    block(seller, buyer.getId());

    // The order still reads back for the buyer and still pays: blocking
    // never strands a live order.
    mockMvc.perform(get("/api/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PENDING"));
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"));
  }
}
