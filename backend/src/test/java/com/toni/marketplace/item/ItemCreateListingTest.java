package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
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
 * {@code POST /api/items}: an authenticated user creates a listing, the
 * seller is taken from the JWT principal (never from the request body),
 * and Jakarta validation violations are answered 400 with the JSON envelope.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users and items it creates
class ItemCreateListingTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository itemRepo;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private User seller;
  private String userToken;

  @BeforeEach
  void createSellerAndToken() {
    seller = users.save(new User("seller-cr", "seller-cr@example.com",
        passwords.encode("seller-password")));
    userToken = jwt.createTokenPair(seller).accessToken();
  }

  private static String body(String title, String description, String priceCents) {
    return "{\"title\":" + title
        + ",\"description\":" + description
        + ",\"priceCents\":" + priceCents + "}";
  }

  @Test
  void authenticatedUserCreatesListing() throws Exception {
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("\"Used ThinkPad T480s\"", "\"good battery\"", "25000")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.message").value("ok"))
        .andExpect(jsonPath("$.data.title").value("Used ThinkPad T480s"))
        .andExpect(jsonPath("$.data.priceCents").value(25000))
        .andExpect(jsonPath("$.data.sellerId").value(seller.getId()))
        .andExpect(jsonPath("$.data.status").value("AVAILABLE"));

    assertThat(itemRepo.findAll())
        .hasSize(1)
        .first().satisfies(item -> {
          assertThat(item.getSellerId()).isEqualTo(seller.getId());
          assertThat(item.getTitle()).isEqualTo("Used ThinkPad T480s");
        });
  }

  @Test
  void blankTitleIsRejectedWith400Envelope() throws Exception {
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("\"   \"", "\"good battery\"", "25000")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.data").isEmpty());

    assertThat(itemRepo.findAll()).isEmpty();
  }

  @Test
  void missingTitleIsRejectedWith400Envelope() throws Exception {
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"description\":\"good battery\",\"priceCents\":25000}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(itemRepo.findAll()).isEmpty();
  }

  @Test
  void zeroAndNegativePriceAreRejected() throws Exception {
    for (String price : new String[]{"0", "-100"}) {
      mockMvc.perform(post("/api/items")
              .header("Authorization", "Bearer " + userToken)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body("\"Used ThinkPad T480s\"", "\"good battery\"", price)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(400));
    }

    assertThat(itemRepo.findAll()).isEmpty();
  }

  @Test
  void missingPriceIsRejected() throws Exception {
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Used ThinkPad T480s\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(itemRepo.findAll()).isEmpty();
  }

  @Test
  void unauthenticatedCreateIsUnauthorized() throws Exception {
    mockMvc.perform(post("/api/items")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("\"Used ThinkPad T480s\"", "\"good battery\"", "25000")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    assertThat(itemRepo.findAll()).isEmpty();
  }
}
