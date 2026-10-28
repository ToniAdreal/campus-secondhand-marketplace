package com.toni.marketplace.message;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * {@code GET /api/messages/search?q=&page=&size=} — cross-thread search
 * (backlog #260).
 *
 * <p>Participant-scoped to the caller's own threads (sent + received,
 * across all listings), body text only, paged like the thread endpoint
 * (page 0 newest, size capped at 50, chronological within the page).
 * A blank {@code q} answers an empty page (the #235 suggestions rule),
 * anonymous callers get 401, and soft-deleted rows never match — their
 * original wording no longer exists in storage (the #206 tombstone).
 *
 * <p>Deliberately NOT {@code @Transactional}: each test owns its rows via
 * unique usernames and wipes the touched tables in {@code cleanUp()} so
 * committed behavior is what gets asserted.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageSearchTest {

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
    String tag = "msgs-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private void send(User sender, User receiver, long itemId, String body) throws Exception {
    String json = "{\"itemId\":" + itemId + ",\"receiverId\":" + receiver.getId()
        + ",\"body\":\"" + body + "\"}";
    mockMvc.perform(post("/api/messages")
            .header("Authorization", "Bearer " + token(sender))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json))
        .andExpect(status().isOk());
  }

  @Test
  void findsOwnSentMessageCaseInsensitively() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = items.save(new Item("Bike lock", "sturdy", 8000L, seller.getId()));
    send(buyer, seller, item.getId(), "does the blue bike lock come with a key");
    send(buyer, seller, item.getId(), "unrelated hello");

    mockMvc.perform(get("/api/messages/search?q=bike")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].body")
            .value("does the blue bike lock come with a key"));
    // Case-insensitive: the uppercase query finds the same row.
    mockMvc.perform(get("/api/messages/search?q=BIKE")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }

  @Test
  void findsOwnReceivedMessageAcrossListings() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item itemA = items.save(new Item("Lamp", "desk", 5000L, seller.getId()));
    Item itemB = items.save(new Item("Chair", "office", 9000L, seller.getId()));
    send(buyer, seller, itemA.getId(), "is the lamp still available");
    // Seller reply: allowed because the buyer already opened a thread (#112).
    send(seller, buyer, itemA.getId(), "yes, pickup code is 4821");
    send(buyer, seller, itemB.getId(), "chair price negotiable?");

    mockMvc.perform(get("/api/messages/search?q=4821")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].body").value("yes, pickup code is 4821"))
        .andExpect(jsonPath("$.data.content[0].itemId").value(itemA.getId().intValue()));
  }

  @Test
  void excludesOtherUsersThreads() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User otherBuyer = newUser();
    Item item = items.save(new Item("Bike lock", "sturdy", 8000L, seller.getId()));
    send(buyer, seller, item.getId(), "my own unrelated note");
    send(otherBuyer, seller, item.getId(), "secret bike deal for others");

    mockMvc.perform(get("/api/messages/search?q=bike")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0))
        .andExpect(jsonPath("$.data.content.length()").value(0));
  }

  @Test
  void blankAndMissingQueryAnswerAnEmptyPage() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = items.save(new Item("Lamp", "desk", 5000L, seller.getId()));
    send(buyer, seller, item.getId(), "is the lamp still available");

    mockMvc.perform(get("/api/messages/search?q=")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));
    mockMvc.perform(get("/api/messages/search?q=%20%20")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));
    mockMvc.perform(get("/api/messages/search")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));
  }

  @Test
  void anonymousGets401() throws Exception {
    mockMvc.perform(get("/api/messages/search?q=bike"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void pagingMirrorsTheThreadEndpoint() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = items.save(new Item("Lamp", "desk", 5000L, seller.getId()));
    for (int i = 1; i <= 60; i++) {
      send(buyer, seller, item.getId(), "searchable note " + i);
    }

    mockMvc.perform(get("/api/messages/search?q=searchable")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(60))
        .andExpect(jsonPath("$.data.content.length()").value(20))
        .andExpect(jsonPath("$.data.content[0].body").value("searchable note 41"))
        .andExpect(jsonPath("$.data.content[19].body").value("searchable note 60"));
    mockMvc.perform(get("/api/messages/search?q=searchable&page=1&size=20")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(jsonPath("$.data.content.length()").value(20))
        .andExpect(jsonPath("$.data.content[0].body").value("searchable note 21"))
        .andExpect(jsonPath("$.data.content[19].body").value("searchable note 40"));
    mockMvc.perform(get("/api/messages/search?q=searchable&size=100")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(jsonPath("$.data.content.length()").value(50))
        .andExpect(jsonPath("$.data.content[0].body").value("searchable note 11"))
        .andExpect(jsonPath("$.data.content[49].body").value("searchable note 60"));
  }

  @Test
  void deletedMessageNoLongerMatches() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = items.save(new Item("Lamp", "desk", 5000L, seller.getId()));
    send(buyer, seller, item.getId(), "retract this bike offer");
    Long messageId = messages.findAll().get(0).getId();

    mockMvc.perform(delete("/api/messages/" + messageId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/messages/search?q=bike")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));
  }

  @Test
  void likeWildcardsInTheQueryMatchLiterally() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = items.save(new Item("Jacket", "cotton", 15000L, seller.getId()));
    send(buyer, seller, item.getId(), "50% off bike gear");
    send(buyer, seller, item.getId(), "50x off bike gear");

    // .param() carries the raw '%' — embedding %25 in the URL string
    // double-encodes under MockMvc and the server sees "50%25".
    mockMvc.perform(get("/api/messages/search").param("q", "50%")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].body").value("50% off bike gear"));
  }
}
