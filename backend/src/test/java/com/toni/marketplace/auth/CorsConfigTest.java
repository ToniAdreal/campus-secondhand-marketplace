package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * CORS allowlist behavior (backlog #63): the Vite dev origin and the
 * compose nginx origin are honored cross-origin; anything else gets no
 * {@code Access-Control-Allow-Origin} header and the browser blocks the
 * response.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorsConfigTest {

  private static final String VITE_ORIGIN = "http://localhost:5173";
  private static final String NGINX_ORIGIN = "http://localhost";
  private static final String UNLISTED_ORIGIN = "https://evil.example";

  /**
   * MockMvc's default request targets {@code http://localhost:80}, which
   * makes {@code Origin: http://localhost} a <em>same-origin</em> request —
   * Spring then skips CORS processing entirely (verified by decompiling
   * {@code CorsUtils.isCorsRequest} in spring-web 6.1.6). In production the
   * browser calls the backend on :8080 while the page is served from :80,
   * so this post-processor moves the request to :8080 to make the nginx
   * origin genuinely cross-origin in the test.
   */
  private static RequestPostProcessor backendOn8080() {
    return request -> {
      request.setServerPort(8080);
      return request;
    };
  }

  @Autowired
  private MockMvc mockMvc;

  @Test
  void preflightFromViteOriginIsAllowed() throws Exception {
    mockMvc.perform(options("/api/items")
            .header("Origin", VITE_ORIGIN)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", VITE_ORIGIN))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
  }

  @Test
  void preflightFromNginxOriginIsAllowed() throws Exception {
    mockMvc.perform(options("/api/items")
            .with(backendOn8080())
            .header("Origin", NGINX_ORIGIN)
            .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", NGINX_ORIGIN));
  }

  @Test
  void preflightFromUnlistedOriginGetsNoAllowHeader() throws Exception {
    mockMvc.perform(options("/api/items")
            .header("Origin", UNLISTED_ORIGIN)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "Authorization"))
        // The preflight is rejected (403 from the CORS filter), but the
        // assertion that matters is the missing header — without it the
        // browser refuses to send the actual request.
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  @Test
  void simpleRequestFromListedOriginCarriesEchoHeader() throws Exception {
    // The request itself is unauthenticated (401 envelope), but CORS still
    // applies: the browser must be able to read the 401 response body.
    mockMvc.perform(get("/api/items").header("Origin", VITE_ORIGIN))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(header().string("Access-Control-Allow-Origin", VITE_ORIGIN));
  }

  @Test
  void simpleRequestFromUnlistedOriginIsRejectedWithoutEchoHeader() throws Exception {
    // Spring's DefaultCorsProcessor fail-closes an actual request from an
    // unlisted origin with 403 ("Invalid CORS request") instead of letting
    // it reach the security chain — no ACAO header either way.
    mockMvc.perform(get("/api/items").header("Origin", UNLISTED_ORIGIN))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }
}
