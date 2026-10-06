package com.toni.marketplace.item;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/categories}: an authenticated user reads the listing
 * category taxonomy — the source the create-listing dropdown is bound to.
 * Anonymous callers get the 401 envelope.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CategoryControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private String userToken;

  @BeforeEach
  void createUserAndToken() {
    User user = users.save(new User("cat-reader", "cat-reader@example.com",
        passwords.encode("some-password")));
    userToken = jwt.createTokenPair(user).accessToken();
  }

  @Test
  void returnsSeededTaxonomyInInsertionOrder() throws Exception {
    mockMvc.perform(get("/api/categories").header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.length()").value(6))
        .andExpect(jsonPath("$.data[0].name").value("Electronics"))
        .andExpect(jsonPath("$.data[0].slug").value("electronics"))
        .andExpect(jsonPath("$.data[1].name").value("Books & Study"))
        .andExpect(jsonPath("$.data[5].name").value("Tickets & Services"));
  }

  @Test
  void anonymousRequestGets401Envelope() throws Exception {
    mockMvc.perform(get("/api/categories"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
