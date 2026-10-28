package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step-up proof for TOTP setup (backlog #250): issuing the shared secret
 * is the sensitive step, mirroring passkey registration — a
 * merely-authenticated session must not be able to enroll its own
 * authenticator and lock the real holder out.
 *
 * <p>Pinned here:
 * <ul>
 *   <li>a fresh token's setup issues the secret + otpauth URI;</li>
 *   <li>a token whose proof is past max-age gets the dedicated 403
 *       step-up envelope on setup and mints nothing — the user row
 *       still has no secret, so a later enable answers 400;</li>
 *   <li>a refused setup leaves a previous enrollment untouched — the
 *       old secret still enables;</li>
 *   <li>re-proving at {@code POST /api/auth/step-up} and retrying setup
 *       with the fresh token succeeds;</li>
 *   <li>the enable flow itself is unchanged: a fresh setup + a valid
 *       code still enables and issues the recovery codes.</li>
 * </ul>
 *
 * <p>The application {@code Clock} bean is replaced with a
 * {@link MutableClock} so proof aging is deterministic, the StepUpTest
 * pattern. TOTP codes are computed at the test clock's instant, since
 * {@link TotpService} verifies against the same injected clock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TotpSetupStepUpTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final AtomicLong SEQ = new AtomicLong();
  private static final AtomicInteger IP_SEQ = new AtomicInteger();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private TotpService totp;
  @Autowired private MutableClock clock;

  private final ObjectMapper json = new ObjectMapper();

  /** Distinct client IPs per call keep the per-IP login rate-limit bucket out of the way. */
  private static RequestPostProcessor uniqueIp() {
    return request -> {
      request.setRemoteAddr("10.92.0." + IP_SEQ.incrementAndGet());
      return request;
    };
  }

  /** Registers a user; registration mints a token whose proof instant is now. */
  private String register(String username) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/register")
            .with(uniqueIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.com\",\"password\":\"s3cret-pass1\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return json.readTree(result.getResponse().getContentAsString())
        .path("data").path("accessToken").asText();
  }

  private String freshUsername() {
    return "totp-setup-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
  }

  private MvcResult postSetup(String bearer) throws Exception {
    return mockMvc.perform(post("/api/auth/2fa/setup")
            .header("Authorization", "Bearer " + bearer))
        .andReturn();
  }

  private void expectStepUp403(MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(403);
    JsonNode body = json.readTree(result.getResponse().getContentAsString());
    assertThat(body.path("code").asInt()).isEqualTo(403);
    assertThat(body.path("message").asText()).contains("re-authentication required");
  }

  private String setupSecret(String bearer) throws Exception {
    MvcResult result = postSetup(bearer);
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    JsonNode data = json.readTree(result.getResponse().getContentAsString()).path("data");
    assertThat(data.path("secret").asText()).isNotBlank();
    assertThat(data.path("otpauthUri").asText()).contains("otpauth://");
    return data.path("secret").asText();
  }

  private User userByUsername(String username) {
    return users.findByUsernameIgnoreCase(username).orElseThrow();
  }

  @Test
  void freshTokenSetupIssuesSecret() throws Exception {
    String username = freshUsername();
    String access = register(username);
    assertThat(setupSecret(access)).isNotBlank();
    assertThat(userByUsername(username).getTotpSecret()).isNotNull();
    assertThat(userByUsername(username).isTotpEnabled()).isFalse();
  }

  @Test
  void staleTokenSetupAnswersStepUp403AndMintsNothing() throws Exception {
    String username = freshUsername();
    String access = register(username);
    clock.advance(Duration.ofMinutes(11));
    expectStepUp403(postSetup(access));
    // Nothing was minted or stored: the row still has no secret, so
    // enable answers 400 ("run the 2fa setup step first") instead of
    // checking a code against a secret the refused setup would have set.
    assertThat(userByUsername(username).getTotpSecret()).isNull();
    mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"000000\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void staleSetupLeavesPreviousSecretUntouched() throws Exception {
    String username = freshUsername();
    String access = register(username);
    String secret = setupSecret(access);
    clock.advance(Duration.ofMinutes(11));
    expectStepUp403(postSetup(access));
    // The old secret still enables (enable itself is ungated and keeps
    // only its code check), proving the refused setup mutated nothing.
    mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + totp.codeAt(secret, clock.instant()) + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.recoveryCodes").isArray());
    assertThat(userByUsername(username).isTotpEnabled()).isTrue();
  }

  @Test
  void stepUpReproofThenSetupSucceeds() throws Exception {
    String username = freshUsername();
    String access = register(username);
    clock.advance(Duration.ofMinutes(11));
    expectStepUp403(postSetup(access));
    MvcResult reproof = mockMvc.perform(post("/api/auth/step-up")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"s3cret-pass1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accessToken").exists())
        .andReturn();
    String freshAccess = json.readTree(reproof.getResponse().getContentAsString())
        .path("data").path("accessToken").asText();
    assertThat(setupSecret(freshAccess)).isNotBlank();
  }

  @Test
  void enableFlowUnchangedAfterFreshSetup() throws Exception {
    String username = freshUsername();
    String access = register(username);
    String secret = setupSecret(access);
    mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + totp.codeAt(secret, clock.instant()) + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.recoveryCodes").isArray());
    assertThat(userByUsername(username).isTotpEnabled()).isTrue();
  }
}
