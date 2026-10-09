package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backlog #89 — TOTP secrets are encrypted at rest, end-to-end via MockMvc:
 * setup stores only the {@code v1:} AES-256-GCM form (never the plaintext
 * Base32 secret it returns), legacy plaintext rows written before #89 still
 * authenticate and are re-encrypted in place, and a tampered stored value
 * fails closed (400 on enable, 401 on authenticate) instead of leaking an
 * exception.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
class TotpEncryptionAtRestTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private TotpService totp;

  @Autowired
  private TotpSecretCipher cipher;

  private String register(String username) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.com\",\"password\":\"s3cret-pass\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
  }

  private String setup(String accessToken) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/2fa/setup")
            .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.secret");
  }

  private void enable(String accessToken, String code) throws Exception {
    mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + code + "\"}"))
        .andExpect(status().isOk());
  }

  /** Logs in with the password and returns the 202 2FA challenge token. */
  private String loginChallenge(String username) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + username
                + "\",\"password\":\"s3cret-pass\"}"))
        .andExpect(status().isAccepted())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.challenge");
  }

  private User storedUser(String username) {
    return users.findByUsernameIgnoreCase(username).orElseThrow();
  }

  private String currentCode(String plainSecret) {
    return totp.codeAt(plainSecret, Instant.now());
  }

  /** Flips one character inside the ciphertext segment of a v1: value. */
  private static String tamper(String stored) {
    int pos = stored.lastIndexOf(':') + 3;
    char flipped = stored.charAt(pos) == 'A' ? 'B' : 'A';
    return stored.substring(0, pos) + flipped + stored.substring(pos + 1);
  }

  @Test
  void setupStoresCiphertextNotPlaintextAndFullFlowStillWorks() throws Exception {
    String access = register("alice");
    String secret = setup(access);

    String stored = storedUser("alice").getTotpSecret();
    assertThat(stored).startsWith(TotpSecretCipher.VERSION_PREFIX);
    assertThat(stored).doesNotContain(secret);
    assertThat(cipher.decryptOrLegacy(stored)).isEqualTo(secret);

    enable(access, currentCode(secret));

    String challenge = loginChallenge("alice");
    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + challenge + "\",\"code\":\""
                + currentCode(secret) + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.accessToken").exists());
  }

  @Test
  void legacyPlaintextSecretStillAuthenticatesAndIsUpgradedInPlace() throws Exception {
    register("bob");
    // Simulate a pre-#89 row: plaintext Base32 secret, 2FA already on.
    String plain = totp.generateSecret();
    User bob = storedUser("bob");
    bob.setTotpSecret(plain);
    bob.setTotpEnabled(true);
    users.save(bob);
    assertThat(storedUser("bob").getTotpSecret()).isEqualTo(plain);

    String challenge = loginChallenge("bob");
    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + challenge + "\",\"code\":\""
                + currentCode(plain) + "\"}"))
        .andExpect(status().isOk());

    String upgraded = storedUser("bob").getTotpSecret();
    assertThat(upgraded).startsWith(TotpSecretCipher.VERSION_PREFIX);
    assertThat(cipher.decryptOrLegacy(upgraded)).isEqualTo(plain);
  }

  @Test
  void legacyPlaintextSecretIsReEncryptedOnEnable() throws Exception {
    String access = register("carol");
    String plain = totp.generateSecret();
    User carol = storedUser("carol");
    carol.setTotpSecret(plain);
    carol.setTotpEnabled(false);
    users.save(carol);

    enable(access, currentCode(plain));

    String stored = storedUser("carol").getTotpSecret();
    assertThat(stored).startsWith(TotpSecretCipher.VERSION_PREFIX);
    assertThat(cipher.decryptOrLegacy(stored)).isEqualTo(plain);
    assertThat(storedUser("carol").isTotpEnabled()).isTrue();
  }

  @Test
  void tamperedStoredSecretFailsClosedOnAuthenticate() throws Exception {
    String access = register("dave");
    String secret = setup(access);
    enable(access, currentCode(secret));

    User dave = storedUser("dave");
    dave.setTotpSecret(tamper(dave.getTotpSecret()));
    users.save(dave);

    String challenge = loginChallenge("dave");
    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + challenge + "\",\"code\":\""
                + currentCode(secret) + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void tamperedStoredSecretFailsClosedOnEnable() throws Exception {
    String access = register("erin");
    String secret = setup(access);

    User erin = storedUser("erin");
    erin.setTotpSecret(tamper(erin.getTotpSecret()));
    users.save(erin);

    mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + currentCode(secret) + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    assertThat(storedUser("erin").isTotpEnabled()).isFalse();
  }
}
