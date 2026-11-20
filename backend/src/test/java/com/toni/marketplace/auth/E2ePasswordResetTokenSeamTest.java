package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * The e2e-only reset-token seam (backlog #407, step C1 unblocking #399)
 * in its OFF state — the default, and every real deployment.
 *
 * <p>Pinned: the {@link PasswordResetMailSender} bean is the logging
 * sender, the read endpoint does not exist (a request to the path 404s),
 * and a reset request issued through the logging sender leaves no token
 * retrievable — the default sender still logs with the token redacted
 * (see {@link LoggingPasswordResetMailSenderTest}) and the token never
 * appears in any response. The on-state is covered by
 * {@link E2ePasswordResetTokenSeamEnabledTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
class E2ePasswordResetTokenSeamTest {

  private static final String REGISTER =
      "{\"username\":\"alice\",\"email\":\"alice@example.com\",\"password\":\"s3cret-pass1\"}";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private PasswordResetMailSender mailSender;

  @Autowired
  private PasswordResetTokenRepository resetTokens;

  @Test
  void offStateWiresTheLoggingSender() {
    assertThat(mailSender).isInstanceOf(LoggingPasswordResetMailSender.class);
  }

  @Test
  void offStateEndpointDoesNotExist() throws Exception {
    mockMvc.perform(get("/api/test/auth/password-reset-token").param("userId", "1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void offStateResetLeavesNoTokenRetrievable() throws Exception {
    MvcResult registered = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REGISTER))
        .andExpect(status().isOk())
        .andReturn();
    long userId = ((Number) JsonPath.read(
        registered.getResponse().getContentAsString(), "$.data.user.id")).longValue();

    // The request itself succeeds identically (no enumeration oracle) and
    // issues a real token row — hash-only storage, as always.
    mockMvc.perform(post("/api/auth/password-reset")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    assertThat(resetTokens.count()).isEqualTo(1);
    assertThat(resetTokens.findAll().get(0).getUserId()).isEqualTo(userId);

    // …but with capture off there is no way to read the raw token back:
    // the endpoint is absent, so the issued token stays unrecoverable.
    mockMvc.perform(get("/api/test/auth/password-reset-token").param("userId", String.valueOf(userId)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }
}
