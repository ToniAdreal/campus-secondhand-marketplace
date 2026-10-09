package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN disable/enable user (backlog #88): POST
 * /api/admin/users/{id}/disable sets the disabled flag (Flyway V18), kills
 * the account's live Bearer tokens (filter check + tokenVersion bump) and
 * revokes every refresh-token family; POST .../enable restores login.
 * Guards: self-disable and ADMIN-on-ADMIN are 403, unknown id is 404,
 * non-admin callers 403, anonymous 401, and a disabled login answers the
 * identical 401 as a bad password.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users and tokens it creates
class AdminUserDisableTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  /** Distinct client IP per login call, so the per-IP login rate-limit
   *  bucket (5/min) never throttles this suite's legitimate logins. */
  private final AtomicInteger ipSequence = new AtomicInteger();

  private User admin;
  private User secondAdmin;
  private User victim;
  private String adminToken;
  private String victimToken;

  @BeforeEach
  void createUsersAndTokens() {
    admin = new User("adm-root", "adm-root@example.com", passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);
    secondAdmin = new User("adm-two", "adm-two@example.com", passwords.encode("admin-password"));
    secondAdmin.setRoles(EnumSet.of(Role.ADMIN));
    secondAdmin = users.save(secondAdmin);
    victim = users.save(new User("adm-victim", "adm-victim@example.com",
        passwords.encode("victim-password")));

    adminToken = jwt.createTokenPair(admin).accessToken();
    victimToken = jwt.createTokenPair(victim).accessToken();
  }

  private MockHttpServletRequestBuilder loginRequest(String username, String password) {
    String ip = "10.88.0." + ipSequence.incrementAndGet();
    return post("/api/auth/login")
        .with(request -> {
          request.setRemoteAddr(ip);
          return request;
        })
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + password + "\"}");
  }

  private void disableAsAdmin(long targetId) throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/disable", targetId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
  }

  @Test
  void adminDisableReturnsNewStateAndPersistsFlag() throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/disable", victim.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.id").value(victim.getId()))
        .andExpect(jsonPath("$.data.username").value("adm-victim"))
        .andExpect(jsonPath("$.data.disabled").value(true));

    assertThat(users.findById(victim.getId()).orElseThrow().isDisabled()).isTrue();
  }

  @Test
  void disabledUserLoginIsIdentical401() throws Exception {
    // Login works before the disable.
    mockMvc.perform(loginRequest("adm-victim", "victim-password"))
        .andExpect(status().isOk());

    disableAsAdmin(victim.getId());

    // Correct password on a disabled account: the same 401 body as a bad
    // password on a live account — no state leak.
    mockMvc.perform(loginRequest("adm-victim", "victim-password"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("invalid credentials"));
    mockMvc.perform(loginRequest("adm-victim", "wrong-password"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("invalid credentials"));
  }

  @Test
  void preDisableBearerTokenIsRejectedAfterDisable() throws Exception {
    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + victimToken))
        .andExpect(status().isOk());

    disableAsAdmin(victim.getId());

    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + victimToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void disableRevokesAllRefreshFamilies() throws Exception {
    // A real login, so the victim holds a live refresh cookie.
    MvcResult login = mockMvc.perform(loginRequest("adm-victim", "victim-password"))
        .andExpect(status().isOk())
        .andReturn();
    Cookie refreshCookie = login.getResponse().getCookie("refresh_token");
    assertThat(refreshCookie).isNotNull();

    disableAsAdmin(victim.getId());

    assertThat(refreshTokens.findAll().stream()
        .filter(row -> row.getUserId().equals(victim.getId()))
        .toList()).isEmpty();
    mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void adminCannotDisableAnotherAdmin() throws Exception {
    String secondAdminToken = jwt.createTokenPair(secondAdmin).accessToken();

    mockMvc.perform(post("/api/admin/users/{id}/disable", secondAdmin.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403))
        .andExpect(jsonPath("$.message").value("cannot disable an admin user"));

    assertThat(users.findById(secondAdmin.getId()).orElseThrow().isDisabled()).isFalse();
    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + secondAdminToken))
        .andExpect(status().isOk());
  }

  @Test
  void adminCannotDisableThemselves() throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/disable", admin.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403))
        .andExpect(jsonPath("$.message").value("cannot disable yourself"));

    assertThat(users.findById(admin.getId()).orElseThrow().isDisabled()).isFalse();
  }

  @Test
  void disableUnknownUserIs404() throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/disable", 999999L)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("user not found"));
  }

  @Test
  void enableUnknownUserIs404() throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/enable", 999999L)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("user not found"));
  }

  @Test
  void nonAdminDisableAndEnableAreForbidden() throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/disable", secondAdmin.getId())
            .header("Authorization", "Bearer " + victimToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    mockMvc.perform(post("/api/admin/users/{id}/enable", victim.getId())
            .header("Authorization", "Bearer " + victimToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(users.findById(victim.getId()).orElseThrow().isDisabled()).isFalse();
  }

  @Test
  void anonymousDisableIsUnauthorized() throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/disable", victim.getId()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    assertThat(users.findById(victim.getId()).orElseThrow().isDisabled()).isFalse();
  }

  @Test
  void enableRestoresLoginButPreDisableBearerStaysDead() throws Exception {
    disableAsAdmin(victim.getId());

    mockMvc.perform(post("/api/admin/users/{id}/enable", victim.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.disabled").value(false));
    assertThat(users.findById(victim.getId()).orElseThrow().isDisabled()).isFalse();

    // Fresh login works again…
    MvcResult login = mockMvc.perform(loginRequest("adm-victim", "victim-password"))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(login.getResponse().getCookie("refresh_token")).isNotNull();

    // …but the token issued before the disable does not resurrect:
    // disable bumped tokenVersion, and enable does not roll it back.
    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + victimToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void disableAndEnableAreIdempotent() throws Exception {
    disableAsAdmin(victim.getId());
    disableAsAdmin(victim.getId());
    assertThat(users.findById(victim.getId()).orElseThrow().isDisabled()).isTrue();

    mockMvc.perform(post("/api/admin/users/{id}/enable", victim.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/admin/users/{id}/enable", victim.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.disabled").value(false));
  }
}
