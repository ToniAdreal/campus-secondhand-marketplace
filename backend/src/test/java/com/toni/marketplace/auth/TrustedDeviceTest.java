package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backlog #199 — trusted devices for the TOTP challenge, end-to-end via
 * MockMvc: a successful exchange with {@code rememberDevice} sets the
 * {@code trusted_device} cookie (hash-only at rest), later password
 * logins from that device skip ONLY the challenge (the token rotates
 * on every use), a replayed rotated-out token kills the device's
 * trust, expiry falls back to the challenge, and trust is revocable
 * one-by-one, via revoke-all-other-sessions, and by a password change.
 * Without the cookie a TOTP account NEVER skips the challenge.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users and trust rows it creates
class TrustedDeviceTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private TrustedDeviceRepository trustedDevices;

  @Autowired
  private TotpService totp;

  // ---- helpers -------------------------------------------------------

  private String register(String username) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.com\",\"password\":\"s3cret-pass1\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
  }

  /** Registers a user and enables TOTP; returns the plain TOTP secret. */
  private String enroll(String username) throws Exception {
    String access = register(username);
    MvcResult setup = mockMvc.perform(post("/api/auth/2fa/setup")
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andReturn();
    String secret = JsonPath.read(setup.getResponse().getContentAsString(), "$.data.secret");
    mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + currentCode(secret) + "\"}"))
        .andExpect(status().isOk());
    return secret;
  }

  private String currentCode(String plainSecret) {
    return totp.codeAt(plainSecret, Instant.now());
  }

  private long userId(String username) {
    return users.findByUsernameIgnoreCase(username).orElseThrow().getId();
  }

  /** Password login; the caller asserts on the returned result (200 pair or 202 challenge). */
  private MvcResult login(String username, Cookie trustedCookie) throws Exception {
    var request = post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"s3cret-pass1\"}");
    if (trustedCookie != null) {
      request = request.cookie(trustedCookie);
    }
    return mockMvc.perform(request).andReturn();
  }

  /** Exchanges a login challenge; rememberDevice drives the trust opt-in. */
  private MvcResult authenticate(String challenge, String code, boolean rememberDevice)
      throws Exception {
    return mockMvc.perform(post("/api/auth/2fa/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + challenge + "\",\"code\":\"" + code
                + "\",\"rememberDevice\":" + rememberDevice + "}"))
        .andReturn();
  }

  private String challengeOf(MvcResult loginResult) throws Exception {
    return JsonPath.read(loginResult.getResponse().getContentAsString(), "$.data.challenge");
  }

  /**
   * Full trusted enrolment: password login → challenge → exchange with
   * the opt-in. Returns the trusted_device cookie the exchange set.
   */
  private Cookie trustDevice(String username, String secret) throws Exception {
    MvcResult login = login(username, null);
    assertThat(login.getResponse().getStatus()).isEqualTo(202);
    MvcResult exchanged = authenticate(challengeOf(login), currentCode(secret), true);
    assertThat(exchanged.getResponse().getStatus()).isEqualTo(200);
    Cookie cookie = exchanged.getResponse().getCookie("trusted_device");
    assertThat(cookie).isNotNull();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getValue()).isNotBlank();
    return cookie;
  }

  private List<?> trustedList(String accessToken) throws Exception {
    MvcResult result = mockMvc.perform(get("/api/auth/trusted-devices")
            .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data");
  }

  // ---- tests ---------------------------------------------------------

  @Test
  void trustThenSkipRotatesTheTokenAndStoresHashOnly() throws Exception {
    String secret = enroll("truster");
    Cookie first = trustDevice("truster", secret);

    // Hash-only at rest: the stored hash is the SHA-256 of the cookie
    // value, never the value itself.
    List<TrustedDevice> rows = trustedDevices.findByUserIdOrderByCreatedAtAsc(userId("truster"));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getTokenHash())
        .isEqualTo(JwtTokenService.sha256Hex(first.getValue()))
        .isNotEqualTo(first.getValue());

    // Trusted login: 200 pair directly (no 202), and the cookie rotates.
    MvcResult trusted = login("truster", first);
    assertThat(trusted.getResponse().getStatus()).isEqualTo(200);
    assertThat((String) JsonPath.read(trusted.getResponse().getContentAsString(),
        "$.data.accessToken")).isNotBlank();
    Cookie rotated = trusted.getResponse().getCookie("trusted_device");
    assertThat(rotated).isNotNull();
    assertThat(rotated.getValue()).isNotEqualTo(first.getValue());

    // The rotated token works for the next login too.
    MvcResult again = login("truster", rotated);
    assertThat(again.getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void replayingARotatedOutTokenKillsTheDeviceTrust() throws Exception {
    String secret = enroll("replayed");
    Cookie first = trustDevice("replayed", secret);
    MvcResult trusted = login("replayed", first);
    assertThat(trusted.getResponse().getStatus()).isEqualTo(200);
    Cookie rotated = trusted.getResponse().getCookie("trusted_device");
    assertThat(rotated).isNotNull();

    // The rotated-out token is replayed: the challenge comes back…
    MvcResult replay = login("replayed", first);
    assertThat(replay.getResponse().getStatus()).isEqualTo(202);
    // …and the trust row is gone, so even the CURRENT token buys nothing.
    assertThat(trustedDevices.findByUserIdOrderByCreatedAtAsc(userId("replayed"))).isEmpty();
    MvcResult after = login("replayed", rotated);
    assertThat(after.getResponse().getStatus()).isEqualTo(202);
  }

  @Test
  void passwordOnlyNeverBypassesWithoutALiveTrustCookie() throws Exception {
    String secret = enroll("cautious");
    // No cookie at all → challenge.
    assertThat(login("cautious", null).getResponse().getStatus()).isEqualTo(202);
    // An unknown cookie value → challenge, and no trust row appears.
    assertThat(login("cautious", new Cookie("trusted_device", "deadbeef".repeat(8)))
        .getResponse().getStatus()).isEqualTo(202);
    assertThat(trustedDevices.findByUserIdOrderByCreatedAtAsc(userId("cautious"))).isEmpty();
    // An exchange WITHOUT the opt-in sets no cookie and creates no trust.
    MvcResult login = login("cautious", null);
    MvcResult exchanged = authenticate(challengeOf(login), currentCode(secret), false);
    assertThat(exchanged.getResponse().getStatus()).isEqualTo(200);
    assertThat(exchanged.getResponse().getCookie("trusted_device")).isNull();
    assertThat(trustedDevices.findByUserIdOrderByCreatedAtAsc(userId("cautious"))).isEmpty();
    assertThat(login("cautious", null).getResponse().getStatus()).isEqualTo(202);
  }

  @Test
  void expiredTrustFallsBackToTheChallengeAndDropsTheRow() throws Exception {
    String secret = enroll("stale");
    Cookie cookie = trustDevice("stale", secret);
    TrustedDevice row = trustedDevices.findByUserIdOrderByCreatedAtAsc(userId("stale")).get(0);
    row.setExpiresAt(Instant.now().minusSeconds(60));
    trustedDevices.save(row);

    assertThat(login("stale", cookie).getResponse().getStatus()).isEqualTo(202);
    assertThat(trustedDevices.findByUserIdOrderByCreatedAtAsc(userId("stale"))).isEmpty();
  }

  @Test
  void trustIsListedAndRevocableOnTheSessionsSurface() throws Exception {
    String secret = enroll("listed");
    Cookie cookie = trustDevice("listed", secret);
    // A fresh login via the challenge (no trust needed) yields an access token.
    MvcResult login = login("listed", null);
    MvcResult exchanged = authenticate(challengeOf(login), currentCode(secret), false);
    String access = JsonPath.read(exchanged.getResponse().getContentAsString(),
        "$.data.accessToken");

    List<?> devices = trustedList(access);
    assertThat(devices).hasSize(1);
    Number id = JsonPath.read(devices.get(0), "$.id");

    // Another user cannot revoke it (404, no cross-user oracle)…
    String stranger = register("stranger");
    mockMvc.perform(delete("/api/auth/trusted-devices/" + id.longValue())
            .header("Authorization", "Bearer " + stranger))
        .andExpect(status().isNotFound());
    assertThat(trustedList(access)).hasSize(1);
    // …and anonymous callers get the 401 envelope.
    mockMvc.perform(get("/api/auth/trusted-devices"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    // The owner revokes it: the list empties and the cookie dies.
    mockMvc.perform(delete("/api/auth/trusted-devices/" + id.longValue())
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk());
    assertThat(trustedList(access)).isEmpty();
    assertThat(login("listed", cookie).getResponse().getStatus()).isEqualTo(202);
  }

  @Test
  void revokeOtherSessionsAlsoRevokesOtherTrustedDevices() throws Exception {
    String secret = enroll("bulkrevoke");
    Cookie cookie = trustDevice("bulkrevoke", secret);

    // A second, ordinary session (challenge exchange, no new trust).
    MvcResult login = login("bulkrevoke", null);
    MvcResult exchanged = authenticate(challengeOf(login), currentCode(secret), false);
    String access = JsonPath.read(exchanged.getResponse().getContentAsString(),
        "$.data.accessToken");
    Cookie refresh = exchanged.getResponse().getCookie("refresh_token");
    assertThat(refresh).isNotNull();
    assertThat(trustedList(access)).hasSize(1);

    // Revoke-others presented WITHOUT the trusted cookie keeps no trust.
    mockMvc.perform(post("/api/auth/sessions/revoke-others")
            .header("Authorization", "Bearer " + access)
            .cookie(refresh))
        .andExpect(status().isOk());
    assertThat(trustedList(access)).isEmpty();
    assertThat(login("bulkrevoke", cookie).getResponse().getStatus()).isEqualTo(202);
  }

  @Test
  void passwordChangeRevokesEveryTrustedDevice() throws Exception {
    String secret = enroll("changer");
    Cookie cookie = trustDevice("changer", secret);
    MvcResult login = login("changer", null);
    MvcResult exchanged = authenticate(challengeOf(login), currentCode(secret), false);
    String access = JsonPath.read(exchanged.getResponse().getContentAsString(),
        "$.data.accessToken");
    assertThat(trustedList(access)).hasSize(1);

    MvcResult changed = mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"s3cret-pass1\",\"newPassword\":\"n3w-secret-pass\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String freshAccess = JsonPath.read(changed.getResponse().getContentAsString(),
        "$.data.accessToken");
    assertThat(trustedList(freshAccess)).isEmpty();

    // The old cookie buys nothing now (login needs the NEW password and
    // still lands on the challenge).
    MvcResult relogin = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"changer\",\"password\":\"n3w-secret-pass\"}")
            .cookie(cookie))
        .andReturn();
    assertThat(relogin.getResponse().getStatus()).isEqualTo(202);
  }
}
