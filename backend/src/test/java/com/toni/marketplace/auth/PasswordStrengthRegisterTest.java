package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password-strength policy at the register endpoint: weak passwords are
 * rejected with a 400 in the {code,message,data} envelope; a compliant
 * password registers normally. Success stays 200 — the established contract
 * of this endpoint (see AuthControllerTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PasswordStrengthRegisterTest {

  @Autowired
  private MockMvc mockMvc;

  private String registerBody(String username, String password) {
    return "{\"username\":\"" + username + "\",\"email\":\"" + username
        + "@example.com\",\"password\":\"" + password + "\"}";
  }

  @Test
  void lettersOnlyPasswordIsRejectedWith400() throws Exception {
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("weakuser1", "abcdefghijkl")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("password must contain both letters and digits"));
  }

  @Test
  void commonPasswordIsRejectedWith400() throws Exception {
    // "qwerty123" satisfies the letters+digits composition rule — it fails
    // on the blocklist, which is exactly what this test pins.
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("weakuser2", "qwerty123")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message")
            .value("password is too common, choose a less predictable one"));
  }

  @Test
  void compliantPasswordRegistersNormally() throws Exception {
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("stronguser", "abcd1234efgh")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.accessToken").exists());
  }

  @Test
  void weakPasswordOnExistingUsernameStillReturns400() throws Exception {
    // Strength is evaluated before the duplicate check — deterministic
    // rejection regardless of the account's existence.
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("stronguser", "abcd1234efgh")))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("stronguser", "abcdefgh")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void shortPasswordIsRejectedWith400() throws Exception {
    // Backlog #131: 11 characters with letters + digits — composition is
    // fine, only the new minimum-length rule fails it.
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("weakuser3", "abcd1234efg")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message")
            .value("password must be at least 12 characters"));
  }
}
