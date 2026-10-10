package com.toni.marketplace.auth;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Actuator exposure rules (backlog #27): {@code /actuator/health} and
 * {@code /actuator/info} are public liveness/readiness probes for the compose
 * stack; every other actuator endpoint ({@code /actuator/**}, currently
 * {@code /actuator/metrics} and {@code /actuator/prometheus}) sits behind the
 * same Bearer auth as the API. {@code /actuator/prometheus} is the
 * Micrometer Prometheus registry export (backlog #57). The liveness /
 * readiness health groups (backlog #128) are public on the same terms:
 * liveness carries livenessState only (never db — a DB outage must not
 * restart-loop the app), readiness carries readinessState + db.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // @AutoConfigureMockMvc disables all metrics export by default
    // (ObservabilityContextCustomizerFactory: management.defaults.metrics.export.enabled=false)
    // — the dedicated actuator tests opt the prometheus export back in so
    // /actuator/prometheus is actually registered in the test context.
    "management.prometheus.metrics.export.enabled=true"
})
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
  void livenessGroupIsPublicAndCarriesOnlyLivenessState() throws Exception {
    mockMvc.perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components.livenessState.status").value("UP"))
        // The whole point of the group split: no db (or any other)
        // component may leak into liveness.
        .andExpect(jsonPath("$.components.db").doesNotExist())
        .andExpect(jsonPath("$.components.readinessState").doesNotExist());
  }

  @Test
  void readinessGroupIsPublicAndCarriesReadinessStateAndDb() throws Exception {
    mockMvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components.readinessState.status").value("UP"))
        .andExpect(jsonPath("$.components.db.status").value("UP"))
        .andExpect(jsonPath("$.components.livenessState").doesNotExist());
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

  @Test
  void prometheusIsBehindAuthForAnonymous() throws Exception {
    mockMvc.perform(get("/actuator/prometheus"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void prometheusExportsJvmMetricsWithBearerToken() throws Exception {
    mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("jvm_")))
        .andExpect(content().string(containsString("# HELP")));
  }
}
