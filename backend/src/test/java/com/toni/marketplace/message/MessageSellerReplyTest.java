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
 * Seller replies inside a listing thread (backlog #112).
 *
 * <p>Before #112 the seller saw the thread read-only in the SPA and —
 * worse — {@code POST /api/messages} checked only existence + not-self,
 * so any user could message any other user about someone else's
 * listing. The send now has a participant guard: buyer → seller is how
 * a thread opens, seller → buyer is a reply and needs an existing
 * thread on that listing, and every other pairing is 403. These tests
 * pin that contract end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageSellerReplyTest {

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
    String tag = "reply-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item newItem(User seller) {
    return items.save(new Item("Desk lamp", "IKEA Tertial", 12000L, seller.getId()));
  }

  private String sendBody(long itemId, long receiverId, String body) {
    return "{\"itemId\":" + itemId + ",\"receiverId\":" + receiverId
        + ",\"body\":\"" + body + "\"}";
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
  void sellerReplyLandsInTheBuyersThreadView() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);

    postMessage(buyer, item.getId(), seller.getId(), "Is this still available?");

    mockMvc.perform(post("/api/messages")
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(sendBody(item.getId(), buyer.getId(), "Yes — pickup this weekend works.")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.senderId").value(seller.getId()))
        .andExpect(jsonPath("$.data.receiverId").value(buyer.getId()))
        .andExpect(jsonPath("$.data.body").value("Yes — pickup this weekend works."));

    // The buyer reads the reply in their own thread view, chronologically.
    mockMvc.perform(get("/api/messages")
            .header("Authorization", "Bearer " + token(buyer))
            .param("itemId", String.valueOf(item.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(2))
        .andExpect(jsonPath("$.data.content[1].body")
            .value("Yes — pickup this weekend works."))
        .andExpect(jsonPath("$.data.content[1].senderId").value(seller.getId()));

    assertThat(messages.findAll()).hasSize(2);
  }

  @Test
  void sellerCannotColdMessageAUserWithNoThreadOnTheListing() throws Exception {
    User seller = newUser();
    User stranger = newUser();
    Item item = newItem(seller);

    mockMvc.perform(post("/api/messages")
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(sendBody(item.getId(), stranger.getId(), "Want to buy my lamp?")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(messages.findAll()).isEmpty();
  }

  @Test
  void messagingAStrangerAboutSomeoneElsesListingIs403() throws Exception {
    User seller = newUser();
    User sender = newUser();
    User stranger = newUser();
    Item item = newItem(seller);

    // Neither party is the listing's seller and no thread exists: this
    // is the pre-#112 free-form send the guard closes.
    mockMvc.perform(post("/api/messages")
            .header("Authorization", "Bearer " + token(sender))
            .contentType(MediaType.APPLICATION_JSON)
            .content(sendBody(item.getId(), stranger.getId(), "Psst, about that lamp…")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(messages.findAll()).isEmpty();
  }

  @Test
  void aSecondBuyerCanStillOpenTheirOwnThreadWithTheSeller() throws Exception {
    User seller = newUser();
    User buyerOne = newUser();
    User buyerTwo = newUser();
    Item item = newItem(seller);

    postMessage(buyerOne, item.getId(), seller.getId(), "First!");
    postMessage(seller, item.getId(), buyerOne.getId(), "Hi, yes it is available.");

    // Buyer two opening a thread is the ordinary buyer → seller flow;
    // buyer one's thread existing must not block or leak into it.
    postMessage(buyerTwo, item.getId(), seller.getId(), "Is it still available?");

    mockMvc.perform(get("/api/messages")
            .header("Authorization", "Bearer " + token(buyerTwo))
            .param("itemId", String.valueOf(item.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].body").value("Is it still available?"));

    assertThat(messages.findAll()).hasSize(3);
  }

  @Test
  void sellerReplyGuardIsPerListingNotPerUserPair() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item lamp = newItem(seller);
    Item chair = items.save(new Item("Chair", "Wooden", 5000L, seller.getId()));

    postMessage(buyer, lamp.getId(), seller.getId(), "About the lamp…");

    // The same buyer has no thread on the chair listing, so a seller
    // "reply" naming the chair is a cold message and is refused.
    mockMvc.perform(post("/api/messages")
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(sendBody(chair.getId(), buyer.getId(), "About the chair…")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(messages.findAll()).hasSize(1);
  }
}
