package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.audit.AuditTargetType;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * POST /api/auth/username end-to-end via MockMvc (backlog #256): the
 * rename is immediate, follows registration's format + canonical rules,
 * conflicts case-insensitively, audits, leaves sessions alive, and
 * frees the old name back to the pool. Backlog #261 adds the step-up
 * freshness gate: a stale token's rename is refused with the dedicated
 * 403 step-up envelope and changes nothing, and a re-proofed token
 * renames on the retry.
 *
 * <p>The application {@code Clock} bean is replaced with a
 * {@link MutableClock} so proof aging is deterministic, the StepUpTest
 * pattern.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
class UsernameChangeTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private MutableClock clock;

  @Autowired
  private AuditLogRepository auditLog;

  private static final String PASSWORD = "s3cret-pass1";

  @Test
  void changeSucceedsCanonicalizesAndKeepsSessionAlive() throws Exception {
    MvcResult registered = register("alice", "alice@example.com");
    String access = accessToken(registered);
    long userId = userId(registered);
    Cookie refresh = registered.getResponse().getCookie("refresh_token");
    assertThat(refresh).isNotNull();

    mockMvc.perform(post("/api/auth/username")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"Alice2\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.username").value("alice2"))
        .andExpect(jsonPath("$.data.id").value(userId));

    // The pre-change access token still authenticates (no tokenVersion
    // bump) and /me already reports the new name.
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("alice2"));

    // The refresh cookie still rotates: the session was not revoked.
    mockMvc.perform(post("/api/auth/refresh").cookie(refresh))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    // Login follows the new name; the old name is now unknown.
    login("alice2", PASSWORD);
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isUnauthorized());

    // Exactly one audit row, actor = target = the account holder.
    assertThat(auditLog.findByActorUserIdOrderByIdAsc(userId))
        .extracting(row -> row.getAction())
        .containsExactly(AuditAction.USERNAME_CHANGED);
    assertThat(auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(
        AuditTargetType.USER, userId)).hasSize(1);
  }

  @Test
  void duplicateNameAnswersConflictCaseInsensitively() throws Exception {
    register("bob", "bob@example.com");
    MvcResult registered = register("alice", "alice@example.com");
    String access = accessToken(registered);

    mockMvc.perform(post("/api/auth/username")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"BOB\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("username is already taken"));

    // Nothing changed: the caller is still alice.
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("alice"));
  }

  @Test
  void ownCurrentNameAnswersConflict() throws Exception {
    MvcResult registered = register("alice", "alice@example.com");
    String access = accessToken(registered);

    mockMvc.perform(post("/api/auth/username")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"ALICE\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message")
            .value("new username must differ from the current username"));
  }

  @Test
  void badFormatAnswers400() throws Exception {
    MvcResult registered = register("alice", "alice@example.com");
    String access = accessToken(registered);

    // Too short (registration's 3–60 rule, mirrored).
    mockMvc.perform(post("/api/auth/username")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"ab\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    // Blank.
    mockMvc.perform(post("/api/auth/username")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    // Too long.
    mockMvc.perform(post("/api/auth/username")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + "a".repeat(61) + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("alice"));
  }

  @Test
  void freedNameReturnsToThePoolImmediately() throws Exception {
    MvcResult alice = register("alice", "alice@example.com");
    String aliceAccess = accessToken(alice);
    long aliceId = userId(alice);
    MvcResult bob = register("bob", "bob@example.com");
    String bobAccess = accessToken(bob);
    long bobId = userId(bob);

    mockMvc.perform(post("/api/auth/username")
            .header("Authorization", "Bearer " + aliceAccess)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"alice2\"}"))
        .andExpect(status().isOk());

    // The freed name is takeable through the same endpoint, by a
    // different account — and the two profiles stay distinct ids.
    mockMvc.perform(post("/api/auth/username")
            .header("Authorization", "Bearer " + bobAccess)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"alice\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(bobId))
        .andExpect(jsonPath("$.data.username").value("alice"));
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + aliceAccess))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(aliceId))
        .andExpect(jsonPath("$.data.username").value("alice2"));
  }

  @Test
  void staleTokenAnswersStepUp403AndChangesNothing() throws Exception {
    MvcResult registered = register("alice", "alice@example.com");
    String access = accessToken(registered);
    long userId = userId(registered);
    // Past the 10-minute proof freshness window (backlog #261).
    clock.advance(Duration.ofMinutes(11));

    MvcResult refused = mockMvc.perform(post("/api/auth/username")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"alice2\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403))
        .andReturn();
    assertThat(JsonPath.<String>read(
        refused.getResponse().getContentAsString(), "$.message"))
        .contains("re-authentication required");

    // Nothing changed: the caller is still alice, and no audit row
    // was written for the refused rename.
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("alice"));
    assertThat(auditLog.findByActorUserIdOrderByIdAsc(userId))
        .extracting(row -> row.getAction())
        .doesNotContain(AuditAction.USERNAME_CHANGED);
  }

  @Test
  void stepUpReproofThenRenameSucceeds() throws Exception {
    MvcResult registered = register("alice", "alice@example.com");
    String access = accessToken(registered);
    clock.advance(Duration.ofMinutes(11));
    mockMvc.perform(post("/api/auth/username")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"alice2\"}"))
        .andExpect(status().isForbidden());

    // Re-prove with the current password (the step-up modal's path),
    // then retry the same rename with the fresh token.
    MvcResult reproof = mockMvc.perform(post("/api/auth/step-up")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String freshAccess = JsonPath.read(
        reproof.getResponse().getContentAsString(), "$.data.accessToken");
    mockMvc.perform(post("/api/auth/username")
            .header("Authorization", "Bearer " + freshAccess)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"alice2\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("alice2"));
  }

  @Test
  void anonymousChangeUsernameReturns401() throws Exception {
    register("alice", "alice@example.com");
    mockMvc.perform(post("/api/auth/username")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"alice2\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("unauthorized"));
  }

  private MvcResult register(String username, String email) throws Exception {
    return mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + email
                + "\",\"password\":\"" + PASSWORD + "\"}"))
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

  private long userId(MvcResult result) throws Exception {
    Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.data.user.id");
    return id.longValue();
  }
}
