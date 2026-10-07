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
 * {@code POST /api/messages} and {@code GET /api/messages?itemId=}.
 *
 * <p>Deliberately NOT {@code @Transactional}: like the idempotency tests,
 * each test owns its rows via unique usernames and wipes the touched tables
 * in {@code cleanUp()} so committed behavior is what gets asserted.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageTest {

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
    String tag = "msg-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item newItem(User seller) {
    return items.save(new Item("Desk lamp", "IKEA Tertial", 12000L, seller.getId()));
  }

  private String sendBody(long itemId, long receiverId, String body) {
    String escaped = body.replace("\\", "\\\\").replace("\"", "\\\"");
    return "{\"itemId\":" + itemId + ",\"receiverId\":" + receiverId
        + ",\"body\":\"" + escaped + "\"}";
  }

  private void postMessage(User sender, long itemId, long receiverId, String body)
      throws Exception {
    mockMvc.perform(post("/api/messages")
            .header("Authorization", "Bearer " + token(sender))
            .contentType(MediaType.APPLICATION_JSON)
            .content(sendBody(itemId, receiverId, body)))
        .andExpect(status().isOk());
  }

  @Test
  void buyerSendsMessageToSeller() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);

    mockMvc.perform(post("/api/messages")
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content(sendBody(item.getId(), seller.getId(), "Is this still available?")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.itemId").value(item.getId()))
        .andExpect(jsonPath("$.data.senderId").value(buyer.getId()))
        .andExpect(jsonPath("$.data.receiverId").value(seller.getId()))
        .andExpect(jsonPath("$.data.body").value("Is this still available?"));

    assertThat(messages.findAll()).hasSize(1);
  }

  @Test
  void threadReturnsBothDirectionsInChronologicalOrder() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);

    postMessage(buyer, item.getId(), seller.getId(), "Is this still available?");
    postMessage(seller, item.getId(), buyer.getId(), "Yes, pickup tomorrow?");
    postMessage(buyer, item.getId(), seller.getId(), "Deal.");

    mockMvc.perform(get("/api/messages")
            .header("Authorization", "Bearer " + token(buyer))
            .param("itemId", String.valueOf(item.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.content.length()").value(3))
        .andExpect(jsonPath("$.data.content[0].body").value("Is this still available?"))
        .andExpect(jsonPath("$.data.content[1].body").value("Yes, pickup tomorrow?"))
        .andExpect(jsonPath("$.data.content[2].body").value("Deal."))
        .andExpect(jsonPath("$.data.content[1].senderId").value(seller.getId()));
  }

  @Test
  void sellerSeesSameThread() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);

    postMessage(buyer, item.getId(), seller.getId(), "Hi, interested!");

    mockMvc.perform(get("/api/messages")
            .header("Authorization", "Bearer " + token(seller))
            .param("itemId", String.valueOf(item.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].receiverId").value(seller.getId()));
  }

  @Test
  void thirdPartyCannotReadTheThread() throws Exception {
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
  }

  @Test
  void unauthenticatedSendIs401() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);

    mockMvc.perform(post("/api/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .content(sendBody(item.getId(), seller.getId(), "Hello")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    assertThat(messages.findAll()).isEmpty();
  }

  @Test
  void sendToUnknownItemIs404() throws Exception {
    User seller = newUser();
    User buyer = newUser();

    mockMvc.perform(post("/api/messages")
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content(sendBody(999999L, seller.getId(), "Hello")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));

    assertThat(messages.findAll()).isEmpty();
  }

  @Test
  void sendToUnknownReceiverIs404() throws Exception {
    User buyer = newUser();
    Item item = newItem(buyer);

    mockMvc.perform(post("/api/messages")
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content(sendBody(item.getId(), 999999L, "Hello")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));

    assertThat(messages.findAll()).isEmpty();
  }

  @Test
  void messagingYourselfIs422() throws Exception {
    User seller = newUser();
    Item item = newItem(seller);

    mockMvc.perform(post("/api/messages")
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(sendBody(item.getId(), seller.getId(), "Note to self")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    assertThat(messages.findAll()).isEmpty();
  }

  @Test
  void blankAndOverlongBodiesAre400() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);

    for (String badBody : new String[]{"   ", "x".repeat(2001)}) {
      mockMvc.perform(post("/api/messages")
              .header("Authorization", "Bearer " + token(buyer))
              .contentType(MediaType.APPLICATION_JSON)
              .content(sendBody(item.getId(), seller.getId(), badBody)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(400));
    }

    assertThat(messages.findAll()).isEmpty();
  }

  @Test
  void missingItemIdParamIs400Envelope() throws Exception {
    User buyer = newUser();

    mockMvc.perform(get("/api/messages")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void threadOnUnknownItemIs404() throws Exception {
    User buyer = newUser();

    mockMvc.perform(get("/api/messages")
            .header("Authorization", "Bearer " + token(buyer))
            .param("itemId", "999999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }
}
