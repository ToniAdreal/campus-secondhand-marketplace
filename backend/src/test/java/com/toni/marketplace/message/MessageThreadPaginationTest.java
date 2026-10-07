package com.toni.marketplace.message;

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
 * {@code GET /api/messages?itemId=&page=&size=} — thread pagination.
 *
 * <p>Page 0 is the <em>newest</em> page (default size 20, cap 50); messages are
 * chronological <em>within</em> the page. The 403 participant check rides on
 * {@code totalElements == 0}, so it holds on every page while a participant
 * on an out-of-range page gets a 200 empty page.
 *
 * <p>Deliberately NOT {@code @Transactional}: each test owns its rows via
 * unique usernames and wipes the touched tables in {@code cleanUp()} so
 * committed behavior is what gets asserted.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageThreadPaginationTest {

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
    String tag = "msgp-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private void seedThread(User sender, User receiver, long itemId, int count) throws Exception {
    for (int i = 1; i <= count; i++) {
      String body = "thread message " + i;
      String json = "{\"itemId\":" + itemId + ",\"receiverId\":" + receiver.getId()
          + ",\"body\":\"" + body + "\"}";
      mockMvc.perform(post("/api/messages")
              .header("Authorization", "Bearer " + token(sender))
              .contentType(MediaType.APPLICATION_JSON)
              .content(json))
          .andExpect(status().isOk());
    }
  }

  private String threadUrl(long itemId) {
    return "/api/messages?itemId=" + itemId;
  }

  @Test
  void defaultPageIsTheNewestTwentyChronologicalWithinThePage() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = items.save(new Item("Desk lamp", "IKEA Tertial", 12000L, seller.getId()));
    seedThread(buyer, seller, item.getId(), 25);

    mockMvc.perform(get(threadUrl(item.getId()))
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.totalElements").value(25))
        .andExpect(jsonPath("$.data.content.length()").value(20))
        // newest page = messages 6..25, chronological within the page
        .andExpect(jsonPath("$.data.content[0].body").value("thread message 6"))
        .andExpect(jsonPath("$.data.content[19].body").value("thread message 25"));
  }

  @Test
  void secondPageHoldsTheOldestMessages() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = items.save(new Item("Desk lamp", "IKEA Tertial", 12000L, seller.getId()));
    seedThread(buyer, seller, item.getId(), 25);

    mockMvc.perform(get(threadUrl(item.getId()) + "&page=1&size=20")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(25))
        .andExpect(jsonPath("$.data.content.length()").value(5))
        .andExpect(jsonPath("$.data.content[0].body").value("thread message 1"))
        .andExpect(jsonPath("$.data.content[4].body").value("thread message 5"));
  }

  @Test
  void outOfRangePageIs200EmptyForParticipants() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = items.save(new Item("Desk lamp", "IKEA Tertial", 12000L, seller.getId()));
    seedThread(buyer, seller, item.getId(), 25);

    mockMvc.perform(get(threadUrl(item.getId()) + "&page=2&size=20")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(25))
        .andExpect(jsonPath("$.data.content.length()").value(0));
  }

  @Test
  void sizeIsCappedAtFifty() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = items.save(new Item("Desk lamp", "IKEA Tertial", 12000L, seller.getId()));
    seedThread(buyer, seller, item.getId(), 60);

    mockMvc.perform(get(threadUrl(item.getId()) + "&size=100")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(60))
        .andExpect(jsonPath("$.data.content.length()").value(50))
        // newest 50 = messages 11..60, chronological within the page
        .andExpect(jsonPath("$.data.content[0].body").value("thread message 11"))
        .andExpect(jsonPath("$.data.content[49].body").value("thread message 60"));
  }

  @Test
  void strangerStillGets403OnAnExplicitPage() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User stranger = newUser();
    Item item = items.save(new Item("Desk lamp", "IKEA Tertial", 12000L, seller.getId()));
    seedThread(buyer, seller, item.getId(), 3);

    mockMvc.perform(get(threadUrl(item.getId()) + "&page=1&size=2")
            .header("Authorization", "Bearer " + token(stranger)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }
}
