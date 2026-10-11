package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Case normalization contract (backlog #47): usernames and emails are
 * canonical-lowercase from registration on, duplicate checks are
 * case-insensitive (409, never a raw constraint error), and login accepts
 * any casing of the identifier.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
class CaseNormalizationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  private static final String MIXED_CASE_REGISTER =
      "{\"username\":\"Alice\",\"email\":\"Alice@Example.com\",\"password\":\"s3cret-pass1\"}";

  @Test
  void registerStoresCanonicalLowercase() throws Exception {
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(MIXED_CASE_REGISTER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.user.username").value("alice"))
        .andExpect(jsonPath("$.data.user.email").value("alice@example.com"));

    User saved = users.findByUsernameIgnoreCase("alice").orElseThrow();
    assertThat(saved.getUsername()).isEqualTo("alice");
    assertThat(saved.getEmail()).isEqualTo("alice@example.com");
  }

  @Test
  void registerRejectsCaseVariantUsernameDuplicate() throws Exception {
    register(MIXED_CASE_REGISTER);

    // "ALICE" is the same account as "alice" — 409, not a raw DB error.
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"ALICE\",\"email\":\"other@example.com\",\"password\":\"s3cret-pass1\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("username is already taken"));
  }

  @Test
  void registerRejectsCaseVariantEmailDuplicate() throws Exception {
    register(MIXED_CASE_REGISTER);

    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"alice2\",\"email\":\"ALICE@EXAMPLE.COM\",\"password\":\"s3cret-pass1\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("email is already registered"));
  }

  @Test
  void loginSucceedsWithAnyCasingOfTheIdentifier() throws Exception {
    register(MIXED_CASE_REGISTER);

    for (String id : new String[]{"ALICE", "Alice", "ALICE@EXAMPLE.COM", "alice@example.com"}) {
      mockMvc.perform(post("/api/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"usernameOrEmail\":\"" + id + "\",\"password\":\"s3cret-pass1\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.code").value(0))
          .andExpect(jsonPath("$.data.user.username").value("alice"));
    }
  }

  private void register(String body) throws Exception {
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk());
  }
}
