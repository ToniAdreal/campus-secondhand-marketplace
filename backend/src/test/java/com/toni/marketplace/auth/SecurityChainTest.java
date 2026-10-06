package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end security filter chain via MockMvc: verifies that
 * {@code /api/auth/**} is public, everything else requires a Bearer access
 * token, and unauthenticated requests get the JSON {@code {code,message,data}}
 * 401 envelope instead of a redirect or HTML error page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates (shared @SpringBootTest DB)
class SecurityChainTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private String accessToken;
  private String refreshToken;

  @BeforeEach
  void createUserAndTokens() {
    User user = users.save(new User("bob-chain", "bob-chain@example.com",
        passwords.encode("s3cret-password")));
    JwtTokenService.TokenPair pair = jwt.createTokenPair(user);
    accessToken = pair.accessToken();
    refreshToken = pair.refreshToken();
  }

  @Test
  void missingTokenReturnsJson401Envelope() throws Exception {
    mockMvc.perform(get("/api/items"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("unauthorized"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void malformedTokenReturns401() throws Exception {
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer not.a.real.token"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void refreshTokenIsNotAcceptedAsBearer() throws Exception {
    // Refresh tokens must be rejected on resource endpoints even though the
    // signature is valid — the filter only accepts the "access" type.
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + refreshToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void validAccessTokenGrantsAccess() throws Exception {
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.message").value("ok"));
  }

  @Test
  void validAccessTokenAlsoProtectsItemDetail() throws Exception {
    mockMvc.perform(get("/api/items/999999").header("Authorization", "Bearer " + accessToken))
        // 404 means the request passed security and reached the controller.
        .andExpect(status().isNotFound());
  }

  @Test
  void unauthenticatedRequestToUnknownEndpointReturns401Not404() throws Exception {
    // Security runs before routing: unknown paths are still guarded.
    mockMvc.perform(get("/api/does-not-exist"))
        .andExpect(status().isUnauthorized());
  }
}
