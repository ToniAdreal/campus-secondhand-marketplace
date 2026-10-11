package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * POST /api/auth/password end-to-end via MockMvc: current-password proof,
 * strength policy on the new password, family revocation for other sessions,
 * and a fresh pair for the current session.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
class PasswordChangeTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  private static final String REGISTER =
      "{\"username\":\"alice\",\"email\":\"alice@example.com\",\"password\":\"s3cret-pass1\"}";

  @Test
  void changePasswordSucceedsAndKeepsCurrentSessionAlive() throws Exception {
    MvcResult registered = register();
    String access = accessToken(registered);
    Cookie first = registered.getResponse().getCookie("refresh_token");
    assertThat(first).isNotNull();

    // A second session for the same user (e.g. another device).
    Cookie second = login("alice", "s3cret-pass1").getResponse().getCookie("refresh_token");
    assertThat(second).isNotNull();
    assertThat(refreshTokens.count()).isEqualTo(2);

    MvcResult changed = mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"s3cret-pass1\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.accessToken").exists())
        .andExpect(header().exists("Set-Cookie"))
        .andReturn();

    Cookie fresh = changed.getResponse().getCookie("refresh_token");
    assertThat(fresh).isNotNull();
    assertThat(fresh.getValue()).isNotBlank();

    // The old family is dead server-side: both earlier sessions' refresh
    // cookies now 401…
    mockMvc.perform(post("/api/auth/refresh").cookie(first))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mockMvc.perform(post("/api/auth/refresh").cookie(second))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    // …but the current session keeps working: its fresh refresh cookie
    // rotates normally and the old password no longer logs in.
    mockMvc.perform(post("/api/auth/refresh").cookie(fresh))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    // Rotation marks the consumed row revoked rather than deleting it (the
    // replacedBy chain is what theft detection / the grace window reads),
    // so the family is: [revoked fresh-cookie row, new live row].
    assertThat(refreshTokens.count()).isEqualTo(2);

    login("alice", "n3w-s3cret12");
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"s3cret-pass1\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void wrongCurrentPasswordReturns401AndChangesNothing() throws Exception {
    MvcResult registered = register();
    String access = accessToken(registered);
    long tokensBefore = refreshTokens.count();

    mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"wrong-pass\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("invalid credentials"));

    // No partial state: nothing revoked, the old password still works.
    assertThat(refreshTokens.count()).isEqualTo(tokensBefore);
    login("alice", "s3cret-pass1");
  }

  @Test
  void weakNewPasswordReturns400() throws Exception {
    String access = accessToken(register());

    // Missing digits (12 letters: long enough to pass the length rule,
    // so the composition rule is the one that fires).
    mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"s3cret-pass1\",\"newPassword\":\"abcdefghijkl\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message")
            .value("password must contain both letters and digits"));

    // On the blocklist.
    mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"s3cret-pass1\",\"newPassword\":\"qwerty123\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message")
            .value("password is too common, choose a less predictable one"));

    login("alice", "s3cret-pass1");
  }

  @Test
  void sameAsCurrentPasswordReturns400() throws Exception {
    MvcResult registered = register();
    String access = accessToken(registered);
    long tokensBefore = refreshTokens.count();

    // The new password is identical to the current one → 400, not a change.
    mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"s3cret-pass1\",\"newPassword\":\"s3cret-pass1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message")
            .value("new password must differ from the current password"));

    // No partial state: nothing revoked, nothing re-encoded — the old
    // password still works and the session count is unchanged.
    assertThat(refreshTokens.count()).isEqualTo(tokensBefore);
    login("alice", "s3cret-pass1");

    // And a genuinely new password still changes it fine.
    mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"s3cret-pass1\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    login("alice", "n3w-s3cret12");
  }

  @Test
  void anonymousChangePasswordReturns401() throws Exception {
    register();
    mockMvc.perform(post("/api/auth/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"s3cret-pass1\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("unauthorized"));
  }

  private MvcResult register() throws Exception {
    return mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REGISTER))
        .andExpect(status().isOk())
        .andReturn();
  }

  private MvcResult login(String usernameOrEmail, String password) throws Exception {
    return mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + usernameOrEmail
                + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
  }

  private String accessToken(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
  }
}
