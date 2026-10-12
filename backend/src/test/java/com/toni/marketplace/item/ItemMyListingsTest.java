package com.toni.marketplace.item;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * "My listings" seller view (backlog #146): {@code GET /api/items?mine=true}
 * resolves the seller server-side from the caller's token and returns only
 * the caller's rows — including RESERVED/SOLD rows a seller needs to
 * manage — while the plain list stays the public all-sellers view. There
 * is no client-supplied sellerId parameter by design (see the controller
 * javadoc for the trade-off).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ItemMyListingsTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private ObjectMapper objectMapper;

  private String sellerToken;
  private String otherToken;

  @BeforeEach
  void createSellersAndTokens() {
    User seller = users.save(new User("seller-mine", "seller-mine@example.com",
        passwords.encode("seller-password")));
    sellerToken = jwt.createTokenPair(seller).accessToken();
    User other = users.save(new User("other-mine", "other-mine@example.com",
        passwords.encode("other-password")));
    otherToken = jwt.createTokenPair(other).accessToken();
  }

  private long createItem(String token, String title) throws Exception {
    String body = mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"description\":\"listing\","
                + "\"priceCents\":1500,\"categoryId\":null}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode root = objectMapper.readTree(body);
    return root.path("data").path("id").asLong();
  }

  private void markSold(String token, long itemId) throws Exception {
    mockMvc.perform(patch("/api/items/" + itemId + "/status")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SOLD\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void mineReturnsOnlyCallersListingsIncludingSold() throws Exception {
    createItem(sellerToken, "Seller desk");
    long soldId = createItem(sellerToken, "Seller lamp");
    createItem(otherToken, "Other bike");
    markSold(sellerToken, soldId);

    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + sellerToken)
            .param("mine", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andExpect(jsonPath("$.data.content[0].title").value("Seller lamp"))
        .andExpect(jsonPath("$.data.content[0].status").value("SOLD"))
        .andExpect(jsonPath("$.data.content[1].title").value("Seller desk"))
        .andExpect(jsonPath("$.data.content[1].status").value("AVAILABLE"));
  }

  @Test
  void mineForOtherSellerReturnsOnlyTheirRows() throws Exception {
    createItem(sellerToken, "Seller desk");
    createItem(otherToken, "Other bike");

    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + otherToken)
            .param("mine", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].title").value("Other bike"));
  }

  @Test
  void withoutMineTheListStillShowsEverySeller() throws Exception {
    createItem(sellerToken, "Seller desk");
    createItem(otherToken, "Other bike");

    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2));
  }

  @Test
  void mineComposesWithKeywordFilter() throws Exception {
    createItem(sellerToken, "Seller desk");
    createItem(sellerToken, "Seller lamp");

    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + sellerToken)
            .param("mine", "true")
            .param("q", "lamp"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].title").value("Seller lamp"));
  }

  @Test
  void mineAnonymousIs401() throws Exception {
    mockMvc.perform(get("/api/items").param("mine", "true"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
