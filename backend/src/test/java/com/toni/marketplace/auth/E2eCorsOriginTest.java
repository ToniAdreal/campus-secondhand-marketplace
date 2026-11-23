package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The e2e origin on the CORS allowlist (backlog #423).
 *
 * <p>The explicit allowlist (backlog #63) covers only
 * {@code http://localhost:5173} and {@code http://localhost}, but the
 * Playwright e2e stack serves the SPA from {@code http://127.0.0.1:4173}
 * — so every browser-driven POST to {@code /api/auth/*} answered 403
 * "Invalid CORS request" and all seventeen flows stuck on {@code /login}
 * (main runs 44882267360 and 44893515480, 25 failed each). The #373
 * bypass only fixed the origin-less API-context 429, which masked the
 * browser 403 behind it: {@code registerViaApi} (no {@code Origin}
 * header) returned 200 while the UI login POST from the same suite
 * 403'd. The e2e stack therefore sets
 * {@code APP_CORS_ALLOWED_ORIGINS=http://127.0.0.1:4173} in
 * {@code frontend/playwright.config.ts} (test seam only; the production
 * defaults are unchanged), and this class pins that the property is
 * honored: with the e2e origin listed, a browser login reaches AuthService
 * instead of dying in the CORS filter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.cors.allowed-origins=http://127.0.0.1:4173"
})
class E2eCorsOriginTest {

  private static final String E2E_ORIGIN = "http://127.0.0.1:4173";

  @Autowired
  private MockMvc mockMvc;

  @Test
  void preflightFromE2eOriginIsAllowed() throws Exception {
    mockMvc.perform(options("/api/items")
            .header("Origin", E2E_ORIGIN)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", E2E_ORIGIN))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
  }

  @Test
  void simpleRequestFromE2eOriginCarriesEchoHeader() throws Exception {
    // Unauthenticated (401 envelope), but CORS still applies: the browser
    // must be able to read the 401 response body.
    mockMvc.perform(get("/api/items").header("Origin", E2E_ORIGIN))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(header().string("Access-Control-Allow-Origin", E2E_ORIGIN));
  }

  @Test
  void loginPostFromE2eOriginReachesAuthInsteadOfCors403() throws Exception {
    // The regression pin for #423: before the e2e origin was listed, this
    // exact request — a browser login POST from http://127.0.0.1:4173 —
    // answered 403 "Invalid CORS request" from the filter and never
    // reached AuthService. With the origin listed it reaches the login
    // handler, which answers the ordinary 401 for unknown credentials.
    mockMvc.perform(post("/api/auth/login")
            .header("Origin", E2E_ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"nobody@example.com\",\"password\":\"WrongPassword123\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(header().string("Access-Control-Allow-Origin", E2E_ORIGIN));
  }
}
