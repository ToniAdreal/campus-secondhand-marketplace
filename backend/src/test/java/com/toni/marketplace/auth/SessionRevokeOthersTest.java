package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revoke-all-other-sessions (backlog #163) end-to-end via MockMvc:
 * {@code POST /api/auth/sessions/revoke-others} revokes every refresh
 * family of the caller EXCEPT the family behind the presented refresh
 * cookie, reports how many families it revoked, and deliberately does
 * not bump tokenVersion — the surviving session's access token and
 * refresh rotation keep working (the scoped contrast with logout's
 * global semantics, backlog #148).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
class SessionRevokeOthersTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void revokeOthersKillsTheOtherFamilyWhileTheCurrentKeepsRotating() throws Exception {
    MvcResult registered = register("alice", "alice@example.com", "s3cret-pass1", "device-a");
    MvcResult loggedIn = login("alice", "s3cret-pass1", "device-b");
    Cookie cookieA = refreshCookie(registered);
    Cookie cookieB = refreshCookie(loggedIn);
    String accessB = accessToken(loggedIn);

    mockMvc.perform(post("/api/auth/sessions/revoke-others")
            .header("Authorization", "Bearer " + accessB)
            .cookie(cookieB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.revokedCount").value(1));

    // The other family's refresh cookie is dead…
    mockMvc.perform(post("/api/auth/refresh").cookie(cookieA))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    // …while the current family still rotates normally.
    MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh").cookie(cookieB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn();
    Cookie cookieB2 = refreshed.getResponse().getCookie("refresh_token");
    assertThat(cookieB2).isNotNull();

    // No tokenVersion bump: the access token issued BEFORE the revoke
    // still authenticates (logout's #148 bump would have killed it).
    MvcResult listed = mockMvc.perform(get("/api/auth/sessions")
            .header("Authorization", "Bearer " + accessB)
            .cookie(cookieB2))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(sessionsOf(listed)).hasSize(1);
    assertThat(sessionsOf(listed).get(0).get("current")).isEqualTo(true);
  }

  @Test
  void revokeOthersCountsEveryOtherFamily() throws Exception {
    register("alice", "alice@example.com", "s3cret-pass1", "device-a");
    MvcResult second = login("alice", "s3cret-pass1", "device-b");
    MvcResult third = login("alice", "s3cret-pass1", "device-c");
    Cookie cookieB = refreshCookie(second);
    Cookie cookieC = refreshCookie(third);

    mockMvc.perform(post("/api/auth/sessions/revoke-others")
            .header("Authorization", "Bearer " + accessToken(second))
            .cookie(cookieB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revokedCount").value(2));

    mockMvc.perform(post("/api/auth/refresh").cookie(cookieC))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/auth/refresh").cookie(cookieB))
        .andExpect(status().isOk());
  }

  @Test
  void revokeOthersWithOnlyTheCurrentSessionRevokesNothing() throws Exception {
    MvcResult registered = register("alice", "alice@example.com", "s3cret-pass1", "device-a");
    Cookie cookieA = refreshCookie(registered);

    mockMvc.perform(post("/api/auth/sessions/revoke-others")
            .header("Authorization", "Bearer " + accessToken(registered))
            .cookie(cookieA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revokedCount").value(0));

    mockMvc.perform(post("/api/auth/refresh").cookie(cookieA))
        .andExpect(status().isOk());
  }

  @Test
  void revokeOthersWithoutACookieRevokesEveryFamily() throws Exception {
    MvcResult registered = register("alice", "alice@example.com", "s3cret-pass1", "device-a");
    MvcResult loggedIn = login("alice", "s3cret-pass1", "device-b");

    // No cookie presented: no family can be identified as "current",
    // so every family counts as an other session.
    mockMvc.perform(post("/api/auth/sessions/revoke-others")
            .header("Authorization", "Bearer " + accessToken(loggedIn)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revokedCount").value(2));

    mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie(registered)))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie(loggedIn)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void revokeOthersLeavesAnotherUsersSessionsAlone() throws Exception {
    MvcResult alice = register("alice", "alice@example.com", "s3cret-pass1", "device-a");
    login("alice", "s3cret-pass1", "device-b");
    MvcResult bob = register("bob", "bob@example.com", "s3cret-pass1", "device-b");
    Cookie bobCookie = refreshCookie(bob);

    mockMvc.perform(post("/api/auth/sessions/revoke-others")
            .header("Authorization", "Bearer " + accessToken(alice))
            .cookie(refreshCookie(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revokedCount").value(1));

    mockMvc.perform(post("/api/auth/refresh").cookie(bobCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  void anonymousCallersGet401() throws Exception {
    mockMvc.perform(post("/api/auth/sessions/revoke-others"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> sessionsOf(MvcResult listed) throws Exception {
    return JsonPath.read(listed.getResponse().getContentAsString(), "$.data");
  }

  private MvcResult register(String username, String email, String password, String userAgent)
      throws Exception {
    var req = post("/api/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + username + "\",\"email\":\"" + email
            + "\",\"password\":\"" + password + "\"}");
    if (userAgent != null) {
      req.header("User-Agent", userAgent);
    }
    return mockMvc.perform(req).andExpect(status().isOk()).andReturn();
  }

  private MvcResult login(String usernameOrEmail, String password, String userAgent)
      throws Exception {
    var req = post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"usernameOrEmail\":\"" + usernameOrEmail + "\",\"password\":\"" + password
            + "\"}");
    if (userAgent != null) {
      req.header("User-Agent", userAgent);
    }
    return mockMvc.perform(req).andExpect(status().isOk()).andReturn();
  }

  private String accessToken(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
  }

  private Cookie refreshCookie(MvcResult result) {
    return result.getResponse().getCookie("refresh_token");
  }
}
