package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.notification.NotificationType;
import jakarta.servlet.http.Cookie;
import java.util.List;
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
 * Session-limit property honoured at a second value (backlog #191):
 * with {@code app.auth.max-sessions-per-user=1} every new login evicts
 * the previously live family, so the account holds exactly one session
 * at all times. Separate class because {@code @TestPropertySource}
 * spins its own context (the pattern of the lockout/rate-limit tests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.auth.max-sessions-per-user=1")
class SessionLimitSingleSessionTest {

  private static final String PASSWORD = "s3cret-pass1";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private UserRepository users;

  @Autowired
  private SessionLimitProperties sessionLimit;

  @Test
  void capOfOneKeepsExactlyTheNewestSession() throws Exception {
    assertThat(sessionLimit.getMaxSessionsPerUser()).isEqualTo(1);

    Cookie registered = register("one-dave");
    long daveId = users.findByUsernameIgnoreCase("one-dave").orElseThrow().getId();

    MvcResult first = loginResult("one-dave");
    // The login evicted the registration session.
    assertThat(sessionCount(accessToken(first))).isEqualTo(1);
    assertThat(evictionsOf(daveId)).isEqualTo(1);
    refresh(registered, 401);
    refresh(refreshCookie(first), 200);

    MvcResult second = loginResult("one-dave");
    // And the next login evicts the first login's session in turn.
    assertThat(sessionCount(accessToken(second))).isEqualTo(1);
    assertThat(evictionsOf(daveId)).isEqualTo(2);
    refresh(refreshCookie(second), 200);
  }

  private long evictionsOf(long userId) {
    return notifications.findAll().stream()
        .filter(n -> n.getRecipientId() == userId)
        .filter(n -> n.getType() == NotificationType.SESSION_EVICTED)
        .count();
  }

  private int sessionCount(String accessToken) throws Exception {
    MvcResult listed = mockMvc.perform(get("/api/auth/sessions")
            .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andReturn();
    List<?> sessions = JsonPath.read(listed.getResponse().getContentAsString(), "$.data");
    return sessions.size();
  }

  private void refresh(Cookie cookie, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/auth/refresh").cookie(cookie))
        .andExpect(status().is(expectedStatus));
  }

  private Cookie register(String username) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.com\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return result.getResponse().getCookie("refresh_token");
  }

  private MvcResult loginResult(String username) throws Exception {
    return mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\""
                + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
  }

  private String accessToken(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
  }

  private Cookie refreshCookie(MvcResult result) {
    return result.getResponse().getCookie("refresh_token");
  }
}
