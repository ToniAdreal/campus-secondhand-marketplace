package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Per-session management (backlog #62) end-to-end via MockMvc:
 * {@code GET /api/auth/sessions} lists the caller's live sessions with the
 * device label captured at login/refresh and flags the presented session
 * current; {@code DELETE /api/auth/sessions/{id}} revokes one session
 * without touching the others.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
class SessionManagementTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Test
  void listShowsTwoSessionsAndFlagsThePresentedOneCurrent() throws Exception {
    MvcResult registered = register("alice", "alice@example.com", "s3cret-pass1", "device-a");
    MvcResult loggedIn = login("alice", "s3cret-pass1", "device-b");
    Cookie cookieB = refreshCookie(loggedIn);
    assertThat(cookieB).isNotNull();

    MvcResult listed = mockMvc.perform(get("/api/auth/sessions")
            .header("Authorization", "Bearer " + accessToken(loggedIn))
            .cookie(cookieB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.length()").value(2))
        .andReturn();

    List<Map<String, Object>> sessions = sessionsOf(listed);
    assertThat(sessions).hasSize(2);
    // Distinct session ids — the id is the family root jti, not the row id.
    assertThat(sessions).extracting(s -> s.get("id")).doesNotHaveDuplicates();

    Map<String, Object> current = sessions.stream()
        .filter(s -> Boolean.TRUE.equals(s.get("current")))
        .findFirst().orElseThrow();
    Map<String, Object> other = sessions.stream()
        .filter(s -> !Boolean.TRUE.equals(s.get("current")))
        .findFirst().orElseThrow();

    // The presented cookie belongs to the device-b login.
    assertThat(current.get("userAgent")).isEqualTo("device-b");
    assertThat(other.get("userAgent")).isEqualTo("device-a");
    // The device label is whatever the client sent; the IP is the servlet
    // container's remote address (127.0.0.1 under MockMvc).
    assertThat(current.get("ipAddress")).isEqualTo("127.0.0.1");
    assertThat(other.get("ipAddress")).isEqualTo("127.0.0.1");
    // createdAt = family root (the login); lastActiveAt = the live row.
    sessions.forEach(s -> {
      assertThat(s.get("createdAt")).isNotNull();
      assertThat(s.get("lastActiveAt")).isNotNull();
    });
  }

  @Test
  void deviceLabelIsNullWhenTheClientSentNoUserAgent() throws Exception {
    MvcResult registered = register("alice", "alice@example.com", "s3cret-pass1", null);

    MvcResult listed = mockMvc.perform(get("/api/auth/sessions")
            .header("Authorization", "Bearer " + accessToken(registered))
            .cookie(refreshCookie(registered)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andReturn();

    Object userAgent = JsonPath.read(listed.getResponse().getContentAsString(),
        "$.data[0].userAgent");
    assertThat(userAgent).isNull();
  }

  @Test
  void deleteRevokesTheOtherSessionWhileTheCallersKeepsRotating() throws Exception {
    MvcResult registered = register("alice", "alice@example.com", "s3cret-pass1", "device-a");
    MvcResult loggedIn = login("alice", "s3cret-pass1", "device-b");
    Cookie cookieA = refreshCookie(registered);
    Cookie cookieB = refreshCookie(loggedIn);

    // The other session's id: the non-current entry when presenting cookieA.
    String otherFamily = otherSessionId(accessToken(registered), cookieA);
    mockMvc.perform(delete("/api/auth/sessions/" + otherFamily)
            .header("Authorization", "Bearer " + accessToken(registered)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    // The other session's refresh token is dead…
    mockMvc.perform(post("/api/auth/refresh").cookie(cookieB))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    // …while the caller's session still rotates normally.
    MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh").cookie(cookieA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn();
    Cookie cookieA2 = refreshed.getResponse().getCookie("refresh_token");
    assertThat(cookieA2).isNotNull();

    MvcResult listed = mockMvc.perform(get("/api/auth/sessions")
            .header("Authorization", "Bearer " + accessToken(refreshed))
            .cookie(cookieA2))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(sessionsOf(listed)).hasSize(1);
  }

  @Test
  void deleteOwnCurrentSessionKillsItsNextRefresh() throws Exception {
    MvcResult registered = register("alice", "alice@example.com", "s3cret-pass1", "device-a");
    Cookie cookieA = refreshCookie(registered);

    String ownFamily = currentSessionId(accessToken(registered), cookieA);
    mockMvc.perform(delete("/api/auth/sessions/" + ownFamily)
            .header("Authorization", "Bearer " + accessToken(registered)))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/auth/refresh").cookie(cookieA))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    // The family row is gone server-side.
    assertThat(refreshTokens.count()).isZero();
  }

  @Test
  void deleteAnotherUsersSessionIs404AndLeavesItAlive() throws Exception {
    MvcResult alice = register("alice", "alice@example.com", "s3cret-pass1", "device-a");
    MvcResult bob = register("bob", "bob@example.com", "s3cret-pass1", "device-b");
    Cookie bobCookie = refreshCookie(bob);

    String bobsFamily = currentSessionId(accessToken(bob), bobCookie);

    // No cross-user oracle: bob's session id reads as "not found" for alice.
    mockMvc.perform(delete("/api/auth/sessions/" + bobsFamily)
            .header("Authorization", "Bearer " + accessToken(alice)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));

    // Bob's session is untouched.
    mockMvc.perform(post("/api/auth/refresh").cookie(bobCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  void anonymousCallersGet401OnBothEndpoints() throws Exception {
    mockMvc.perform(get("/api/auth/sessions"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mockMvc.perform(delete("/api/auth/sessions/does-not-matter"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  /** The session id of the non-current entry when presenting {@code cookie}. */
  private String otherSessionId(String access, Cookie cookie) throws Exception {
    MvcResult listed = mockMvc.perform(get("/api/auth/sessions")
            .header("Authorization", "Bearer " + access)
            .cookie(cookie))
        .andExpect(status().isOk())
        .andReturn();
    return sessionsOf(listed).stream()
        .filter(s -> !Boolean.TRUE.equals(s.get("current")))
        .findFirst().orElseThrow()
        .get("id").toString();
  }

  /** The session id flagged current for the presented {@code cookie}. */
  private String currentSessionId(String access, Cookie cookie) throws Exception {
    MvcResult listed = mockMvc.perform(get("/api/auth/sessions")
            .header("Authorization", "Bearer " + access)
            .cookie(cookie))
        .andExpect(status().isOk())
        .andReturn();
    return sessionsOf(listed).stream()
        .filter(s -> Boolean.TRUE.equals(s.get("current")))
        .findFirst().orElseThrow()
        .get("id").toString();
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
