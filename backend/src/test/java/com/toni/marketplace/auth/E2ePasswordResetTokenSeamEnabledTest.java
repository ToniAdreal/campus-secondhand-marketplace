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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * The e2e-only reset-token seam (backlog #407, step C1 unblocking #399)
 * in its ON state — the throwaway Playwright e2e backend only, via
 * {@code APP_AUTH_PASSWORD_RESET_E2E_TOKEN_CAPTURE_ENABLED=true}.
 *
 * <p>Pinned: the {@link PasswordResetMailSender} bean is the capturing
 * sender, the read endpoint serves the latest raw token for the user, a
 * fresh request supersedes the previous token (the old one 401s on
 * confirm), and the token rules themselves are unchanged — a consumed
 * token 401s, and the database still stores only the SHA-256 hash. The
 * off-state is covered by {@link E2ePasswordResetTokenSeamTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
@TestPropertySource(properties = "app.auth.password-reset.e2e-token-capture-enabled=true")
class E2ePasswordResetTokenSeamEnabledTest {

  private static final String REGISTER =
      "{\"username\":\"alice\",\"email\":\"alice@example.com\",\"password\":\"s3cret-pass1\"}";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private PasswordResetMailSender mailSender;

  @Autowired
  private PasswordResetTokenRepository resetTokens;

  private long registerAlice() throws Exception {
    MvcResult registered = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REGISTER))
        .andExpect(status().isOk())
        .andReturn();
    return ((Number) JsonPath.read(
        registered.getResponse().getContentAsString(), "$.data.user.id")).longValue();
  }

  private void requestReset() throws Exception {
    mockMvc.perform(post("/api/auth/password-reset")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  private String readToken(long userId) throws Exception {
    MvcResult result = mockMvc.perform(
            get("/api/test/auth/password-reset-token").param("userId", String.valueOf(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.token");
  }

  @Test
  void onStateWiresTheCapturingSender() {
    assertThat(mailSender).isInstanceOf(CapturingPasswordResetMailSender.class);
  }

  @Test
  void unknownUserAndNoTokenBoth404() throws Exception {
    // No token was ever issued: the endpoint leaks nothing about which
    // ids are accounts — unknown ids and known-but-tokenless users
    // answer the same 404.
    mockMvc.perform(get("/api/test/auth/password-reset-token").param("userId", "999999999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    long userId = registerAlice();
    mockMvc.perform(get("/api/test/auth/password-reset-token").param("userId", String.valueOf(userId)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void readTokenConfirmsTheResetAndLogsInWithTheNewPassword() throws Exception {
    long userId = registerAlice();
    requestReset();
    String token = readToken(userId);
    assertThat(token).isNotBlank();
    // Only the hash is stored: the row's hash is not the raw token.
    assertThat(resetTokens.count()).isEqualTo(1);
    assertThat(resetTokens.findAll().get(0).getTokenHash()).isNotEqualTo(token);

    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    // The token is consumed: a second confirm with it 401s (token rules
    // unchanged by the seam).
    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"an0ther-s3cret\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    // Old password is gone, new password logs in.
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"s3cret-pass1\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"n3w-s3cret12\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  void freshRequestSupersedesThePreviousToken() throws Exception {
    long userId = registerAlice();
    requestReset();
    String firstToken = readToken(userId);

    requestReset();
    String secondToken = readToken(userId);
    assertThat(secondToken).isNotBlank().isNotEqualTo(firstToken);

    // The superseded token is dead: confirming with it 401s, while the
    // latest token still confirms.
    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + firstToken + "\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + secondToken + "\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }
}
