package com.toni.marketplace.item;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Search suggestions (backlog #235): {@code GET /api/items/suggestions?q=}
 * matches titles by prefix only, caps at 8, answers an empty list for a
 * blank query, and stays behind the same authentication as browse.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users and items it creates
class ItemSuggestionsTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private String sellerToken;
  private User seller;

  @BeforeEach
  void createSellerAndToken() {
    seller = users.save(new User("seller-suggest", "seller-suggest@example.com",
        passwords.encode("seller-password")));
    sellerToken = jwt.createTokenPair(seller).accessToken();
  }

  private void createItem(String title, String description) throws Exception {
    createItem(title, description, null);
  }

  private void createItem(String title, String description, String status) throws Exception {
    String statusField = status == null ? "" : ",\"status\":\"" + status + "\"";
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"description\":\"" + description
                + "\",\"priceCents\":1000" + statusField + "}"))
        .andExpect(status().isOk());
  }

  @Test
  void prefixMatchesTitleOnlyAndIsCaseInsensitive() throws Exception {
    createItem("Bike helmet", "road gear");
    createItem("Bike lock", "sturdy");
    createItem("Mountain bike", "bike in the title, not the prefix");
    createItem("Lamp", "bike light for night rides");

    mockMvc.perform(get("/api/items/suggestions")
            .header("Authorization", "Bearer " + sellerToken)
            .param("q", "BIKE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].title").value("Bike helmet"))
        .andExpect(jsonPath("$.data[1].title").value("Bike lock"));
  }

  @Test
  void resultsAreCappedAtEightInAlphabeticalOrder() throws Exception {
    for (int i = 10; i >= 1; i--) {
      createItem(String.format("Bike part %02d", i), "spare");
    }

    mockMvc.perform(get("/api/items/suggestions")
            .header("Authorization", "Bearer " + sellerToken)
            .param("q", "bike"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(8))
        .andExpect(jsonPath("$.data[0].title").value("Bike part 01"))
        .andExpect(jsonPath("$.data[7].title").value("Bike part 08"));
  }

  @Test
  void blankAndMissingQueryAnswerAnEmptyList() throws Exception {
    createItem("Bike helmet", "road gear");

    mockMvc.perform(get("/api/items/suggestions")
            .header("Authorization", "Bearer " + sellerToken)
            .param("q", "   "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));

    mockMvc.perform(get("/api/items/suggestions")
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void draftsAreExcludedFromSuggestions() throws Exception {
    createItem("Bike helmet", "road gear", "DRAFT");
    createItem("Bike lock", "sturdy");

    mockMvc.perform(get("/api/items/suggestions")
            .header("Authorization", "Bearer " + sellerToken)
            .param("q", "bike"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].title").value("Bike lock"));
  }

  @Test
  void anonymousCallersGet401LikeBrowse() throws Exception {
    mockMvc.perform(get("/api/items/suggestions").param("q", "bike"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void blockedSellersListingsAreExcludedForTheBlockerOnly() throws Exception {
    User other = users.save(new User("seller-suggest-other", "seller-suggest-other@example.com",
        passwords.encode("other-password")));
    String otherToken = jwt.createTokenPair(other).accessToken();
    // The other seller's listing is created under their own token.
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + otherToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Bike pump\",\"description\":\"floor pump\",\"priceCents\":1000}"))
        .andExpect(status().isOk());
    createItem("Bike helmet", "road gear");

    // Before blocking, the caller sees both titles.
    mockMvc.perform(get("/api/items/suggestions")
            .header("Authorization", "Bearer " + sellerToken)
            .param("q", "bike"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2));

    mockMvc.perform(post("/api/users/{id}/block", other.getId())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk());

    // After blocking, only the caller's own title remains.
    mockMvc.perform(get("/api/items/suggestions")
            .header("Authorization", "Bearer " + sellerToken)
            .param("q", "bike"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].title").value("Bike helmet"));
  }
}
