package com.toni.marketplace.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Message edit + soft delete (backlog #206): {@code PUT /api/messages/{id}}
 * is sender-only inside {@code app.messages.edit-window} (default
 * 15 minutes from send, boundary inclusive), {@code DELETE
 * /api/messages/{id}} is sender-only soft delete — the row stays as a
 * tombstone and stops counting as unread at once.
 *
 * <p>The window boundary is exercised by backdating {@code created_at}
 * through JDBC (the column is {@code updatable = false} for JPA — the
 * send instant is a fact, only the test moves it), against the real
 * clock the service reads: 14 minutes old edits fine, 16 minutes old
 * is 422.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link MessageTest}: committed behaviour is what gets asserted.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageEditDeleteTest {

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

  @Autowired
  private JdbcTemplate jdbc;

  @AfterEach
  void cleanUp() {
    messages.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser() {
    String tag = "msge-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item newItem(User seller) {
    return items.save(new Item("Desk lamp", "IKEA Tertial", 12000L, seller.getId()));
  }

  /** Sends buyer → seller and returns the stored row. */
  private Message send(User buyer, Item item, User seller, String body) throws Exception {
    mockMvc.perform(post("/api/messages")
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + ",\"receiverId\":" + seller.getId()
                + ",\"body\":\"" + body + "\"}"))
        .andExpect(status().isOk());
    return messages.findAll().get(0);
  }

  private void backdate(Message message, Duration age) {
    jdbc.update("update messages set created_at = ? where id = ?",
        Timestamp.from(Instant.now().minus(age)), message.getId());
  }

  private void edit(User as, long messageId, String body, int expectedStatus) throws Exception {
    var call = mockMvc.perform(put("/api/messages/" + messageId)
            .header("Authorization", "Bearer " + token(as))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"" + body + "\"}"))
        .andExpect(status().is(expectedStatus))
        .andExpect(jsonPath("$.code").value(expectedStatus == 200 ? 0 : expectedStatus));
    if (expectedStatus == 200) {
      call.andExpect(jsonPath("$.data.body").value(body))
          .andExpect(jsonPath("$.data.editedAt").isNotEmpty())
          .andExpect(jsonPath("$.data.deletedAt")
              .value(org.hamcrest.Matchers.nullValue()));
    }
  }

  @Test
  void senderEditsWithinTheWindow() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    Message sent = send(buyer, item, seller, "Is this still availabel?");
    Instant createdAt = sent.getCreatedAt();

    edit(buyer, sent.getId(), "Is this still available?", 200);

    Message stored = messages.findById(sent.getId()).orElseThrow();
    assertThat(stored.getBody()).isEqualTo("Is this still available?");
    assertThat(stored.getEditedAt()).isNotNull();
    // An edit never moves createdAt — thread order/pagination are keyed on it.
    assertThat(stored.getCreatedAt()).isEqualTo(createdAt);

    // The receiver's thread read shows the edited body + marker.
    mockMvc.perform(get("/api/messages")
            .header("Authorization", "Bearer " + token(seller))
            .param("itemId", String.valueOf(item.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].body").value("Is this still available?"))
        .andExpect(jsonPath("$.data.content[0].editedAt").isNotEmpty());
  }

  @Test
  void editJustInsideTheWindowIsAccepted() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    Message sent = send(buyer, item, seller, "Pickup at 6?");
    backdate(sent, Duration.ofMinutes(14));

    edit(buyer, sent.getId(), "Pickup at 7?", 200);
  }

  @Test
  void editAfterTheWindowIs422AndChangesNothing() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    Message sent = send(buyer, item, seller, "Pickup at 6?");
    backdate(sent, Duration.ofMinutes(16));

    edit(buyer, sent.getId(), "Pickup at 7?", 422);

    Message stored = messages.findById(sent.getId()).orElseThrow();
    assertThat(stored.getBody()).isEqualTo("Pickup at 6?");
    assertThat(stored.getEditedAt()).isNull();
  }

  @Test
  void nonSenderCannotEdit() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    Message sent = send(buyer, item, seller, "Is this still available?");

    // The receiver (here the seller) has no edit right over the buyer's words.
    edit(seller, sent.getId(), "Rewritten by the seller", 403);

    assertThat(messages.findById(sent.getId()).orElseThrow().getBody())
        .isEqualTo("Is this still available?");
  }

  @Test
  void editUnknownMessageIs404AndAnonymousIs401() throws Exception {
    User buyer = newUser();

    edit(buyer, 999999L, "hello?", 404);

    mockMvc.perform(put("/api/messages/1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"hello?\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mockMvc.perform(delete("/api/messages/1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void blankEditBodyIs400() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    Message sent = send(buyer, item, seller, "Is this still available?");

    mockMvc.perform(put("/api/messages/" + sent.getId())
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(messages.findById(sent.getId()).orElseThrow().getEditedAt()).isNull();
  }

  @Test
  void senderSoftDeletesToATombstoneAndUnreadCountDropsAtOnce() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    Message sent = send(buyer, item, seller, "Wrong listing, sorry");

    // The seller has one unread message before the delete.
    mockMvc.perform(get("/api/messages/unread-count")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(1));

    mockMvc.perform(delete("/api/messages/" + sent.getId())
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.body").value(Message.TOMBSTONE_BODY))
        .andExpect(jsonPath("$.data.deletedAt").isNotEmpty());

    // The row is kept, its stored body IS the tombstone (the original
    // wording is gone from storage, not merely hidden at read time).
    Message stored = messages.findById(sent.getId()).orElseThrow();
    assertThat(stored.isDeleted()).isTrue();
    assertThat(stored.getBody()).isEqualTo(Message.TOMBSTONE_BODY);

    // Delete-then-unread-count pin: the retracted message stops
    // counting immediately, without the seller opening the thread.
    mockMvc.perform(get("/api/messages/unread-count")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(0));

    // The thread still holds the row, in place, as a tombstone — the
    // seller's context (order of the conversation) survives.
    mockMvc.perform(get("/api/messages")
            .header("Authorization", "Bearer " + token(seller))
            .param("itemId", String.valueOf(item.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].body").value(Message.TOMBSTONE_BODY))
        .andExpect(jsonPath("$.data.content[0].deletedAt").isNotEmpty());

    // A repeat delete is an idempotent 200 with the same tombstone.
    mockMvc.perform(delete("/api/messages/" + sent.getId())
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.body").value(Message.TOMBSTONE_BODY));
  }

  @Test
  void nonSenderCannotDelete() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    Message sent = send(buyer, item, seller, "Is this still available?");

    mockMvc.perform(delete("/api/messages/" + sent.getId())
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    Message stored = messages.findById(sent.getId()).orElseThrow();
    assertThat(stored.isDeleted()).isFalse();
    assertThat(stored.getBody()).isEqualTo("Is this still available?");
  }

  @Test
  void editingADeletedMessageIs422() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    Message sent = send(buyer, item, seller, "Wrong listing, sorry");

    mockMvc.perform(delete("/api/messages/" + sent.getId())
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());

    edit(buyer, sent.getId(), "Edited after delete", 422);
  }

  @Test
  void deleteUnknownMessageIs404() throws Exception {
    User buyer = newUser();

    mockMvc.perform(delete("/api/messages/999999")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }
}
