package com.toni.marketplace.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Message read state (backlog #106): {@code GET /api/messages?itemId=}
 * stamps the caller-as-receiver rows read in the same transaction, and
 * {@code GET /api/messages/unread-count} reports the caller's total
 * unread across all listings.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring {@link MessageTest}:
 * committed behavior (the stamp must survive the thread request's own
 * transaction) is exactly what is being asserted.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageReadStateTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private MessageRepository messages;

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
    messages.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser() {
    String tag = "msgr-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item newItem(User seller) {
    return items.save(new Item("Desk lamp", "IKEA Tertial", 12000L, seller.getId()));
  }

  private void postMessage(User sender, long itemId, long receiverId, String body)
      throws Exception {
    mockMvc.perform(post("/api/messages")
            .header("Authorization", "Bearer " + token(sender))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + itemId + ",\"receiverId\":" + receiverId
                + ",\"body\":\"" + body + "\"}"))
        .andExpect(status().isOk());
  }

  private void expectUnreadCount(User user, long expected) throws Exception {
    mockMvc.perform(get("/api/messages/unread-count")
            .header("Authorization", "Bearer " + token(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data").value(expected));
  }

  @Test
  void threadViewMarksOnlyTheCallersReceivedRows() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);

    postMessage(buyer, item.getId(), seller.getId(), "Is this still available?");
    postMessage(seller, item.getId(), buyer.getId(), "Yes, pickup tomorrow?");
    postMessage(buyer, item.getId(), seller.getId(), "Deal.");

    expectUnreadCount(seller, 2);
    expectUnreadCount(buyer, 1);

    // The seller opens the thread: their two received rows are stamped
    // (and the returned DTOs already carry readAt), the row they sent
    // stays unread for the buyer.
    mockMvc.perform(get("/api/messages")
            .header("Authorization", "Bearer " + token(seller))
            .param("itemId", String.valueOf(item.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].readAt").isNotEmpty())
        .andExpect(jsonPath("$.data.content[1].readAt").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.data.content[2].readAt").isNotEmpty());

    expectUnreadCount(seller, 0);
    expectUnreadCount(buyer, 1);
    assertThat(messages.findAll())
        .filteredOn(m -> m.getReceiverId().equals(seller.getId()))
        .allMatch(m -> m.getReadAt() != null);
    assertThat(messages.findAll())
        .filteredOn(m -> m.getReceiverId().equals(buyer.getId()))
        .allMatch(m -> m.getReadAt() == null);
  }

  @Test
  void senderViewMarksNothing() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);

    postMessage(buyer, item.getId(), seller.getId(), "Is this still available?");

    // The buyer (sender-only so far) opens the thread: nothing is theirs
    // to mark, so the seller's unread count does not move.
    mockMvc.perform(get("/api/messages")
            .header("Authorization", "Bearer " + token(buyer))
            .param("itemId", String.valueOf(item.getId())))
        .andExpect(status().isOk());

    expectUnreadCount(seller, 1);
    assertThat(messages.findAll()).allMatch(m -> m.getReadAt() == null);
  }

  @Test
  void nonParticipantForbiddenViewMarksNothing() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User stranger = newUser();
    Item item = newItem(seller);

    postMessage(buyer, item.getId(), seller.getId(), "Is this still available?");

    mockMvc.perform(get("/api/messages")
            .header("Authorization", "Bearer " + token(stranger))
            .param("itemId", String.valueOf(item.getId())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    expectUnreadCount(seller, 1);
    assertThat(messages.findAll()).allMatch(m -> m.getReadAt() == null);
  }

  @Test
  void unreadCountIsZeroWithNoMessagesAndScopedToTheCaller() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);

    expectUnreadCount(seller, 0);
    postMessage(buyer, item.getId(), seller.getId(), "Hi!");
    expectUnreadCount(buyer, 0);
    expectUnreadCount(seller, 1);
  }

  @Test
  void unreadCountAnonymousIs401() throws Exception {
    mockMvc.perform(get("/api/messages/unread-count"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
