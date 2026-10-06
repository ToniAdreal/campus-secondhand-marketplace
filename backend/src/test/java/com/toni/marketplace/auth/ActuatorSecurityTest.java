package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Actuator exposure rules (backlog #27): {@code /actuator/health} and
 * {@code /actuator/info} are public liveness/readiness probes for the compose
 * stack; every other actuator endpoint ({@code /actuator/**}, currently only
 * {@code /actuator/metrics}) sits behind the same Bearer auth as the API.
 * No external metrics export is configured (follow-up).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates (shared @SpringBootTest DB)
class ActuatorSecurityTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private String accessToken;

  @BeforeEach
  void createUserAndTokens() {
    User user = users.save(new User("act-observer", "act-observer@example.com",
        passwords.encode("s3cret-password")));
    accessToken = jwt.createTokenPair(user).accessToken();
  }

  @Test
  void healthIsPublicAndReportsUp() throws Exception {
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void infoIsPublic() throws Exception {
    mockMvc.perform(get("/actuator/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.app.name").value("campus-secondhand-marketplace"));
  }

  @Test
  void metricsIsBehindAuthForAnonymous() throws Exception {
    mockMvc.perform(get("/actuator/metrics"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void metricsIsReachableWithBearerToken() throws Exception {
    mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.names").isArray());
  }
}
