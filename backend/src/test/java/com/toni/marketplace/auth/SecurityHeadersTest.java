package com.toni.marketplace.auth;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Security response headers (backlog #64). Every response — public,
 * authenticated, and even the 401 envelope — must carry
 * {@code X-Content-Type-Options: nosniff}, {@code X-Frame-Options: DENY} and
 * {@code Referrer-Policy: no-referrer}. HSTS
 * ({@code Strict-Transport-Security}) is off by default (local plain-HTTP
 * dev); the opt-in class below asserts it engages when
 * {@code app.security.headers.hsts-enabled=true}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates (shared @SpringBootTest DB)
class SecurityHeadersTest {

  private static RequestPostProcessor secureRequest() {
    return request -> {
      request.setSecure(true);
      return request;
    };
  }

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
    User user = users.save(new User("headers-user", "headers-user@example.com",
        passwords.encode("s3cret-password")));
    accessToken = jwt.createTokenPair(user).accessToken();
  }

  @Test
  void publicEndpointSendsSecurityHeaders() throws Exception {
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"));
  }

  @Test
  void authenticatedEndpointSendsSecurityHeaders() throws Exception {
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"));
  }

  @Test
  void unauthorizedEnvelopeStillSendsSecurityHeaders() throws Exception {
    // The filter-layer 401 JSON envelope (anonymous /api/auth/me) goes
    // through the same HeaderWriterFilter as every other response.
    mockMvc.perform(get("/api/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"));
  }

  @Test
  void hstsIsOffByDefaultEvenOnSecureRequests() throws Exception {
    // HSTS is opt-in via app.security.headers.hsts-enabled (off by default
    // for local plain-HTTP dev): even a secure request must not get
    // Strict-Transport-Security from the default configuration.
    mockMvc.perform(get("/actuator/health").with(secureRequest()))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Strict-Transport-Security"));
  }

  @Test
  void cspIsOffByDefault() throws Exception {
    // CSP is opt-in via app.security.headers.csp-enabled (backlog #80,
    // off by default like HSTS): the default configuration must not send
    // a Content-Security-Policy header on public or authenticated
    // endpoints.
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Content-Security-Policy"));
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Content-Security-Policy"));
  }
}

/**
 * HSTS opt-in half of backlog #64: with
 * {@code app.security.headers.hsts-enabled=true} a secure request gets
 * {@code Strict-Transport-Security: max-age=31536000 ; includeSubDomains}.
 * Separate class because @TestPropertySource spins a new context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.security.headers.hsts-enabled=true")
class SecurityHeadersHstsEnabledTest {

  private static RequestPostProcessor secureRequest() {
    return request -> {
      request.setSecure(true);
      return request;
    };
  }

  @Autowired
  private MockMvc mockMvc;

  @Test
  void hstsEnabledSendsStrictTransportSecurity() throws Exception {
    // Spring's HstsHeaderWriter writes only for secure requests (its default
    // matcher is EL "isSecure()"), so the test request is marked secure —
    // like an HTTPS deployment behind TLS termination.
    mockMvc.perform(get("/actuator/health").with(secureRequest()))
        .andExpect(status().isOk())
        .andExpect(header().string("Strict-Transport-Security",
            allOf(containsString("max-age=31536000"), containsString("includeSubDomains"))))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"));
  }
}

/**
 * CSP opt-in half of backlog #80: with
 * {@code app.security.headers.csp-enabled=true} every response carries the
 * strict policy from {@code SecurityConfig} — {@code 'self'} everywhere,
 * {@code data:} only for images, {@code ws:} only for Vite HMR, and no
 * {@code 'unsafe-inline'} (verified unnecessary against the real Vite
 * build output: external bundles only). The other #64 headers stay
 * untouched. Separate class because @TestPropertySource spins a new
 * context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.security.headers.csp-enabled=true")
class SecurityHeadersCspEnabledTest {

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
    User user = users.save(new User("csp-user", "csp-user@example.com",
        passwords.encode("s3cret-password")));
    accessToken = jwt.createTokenPair(user).accessToken();
  }

  @Test
  void cspEnabledSendsStrictPolicyOnPublicEndpoint() throws Exception {
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Security-Policy",
            SecurityConfig.CONTENT_SECURITY_POLICY))
        .andExpect(header().string("Content-Security-Policy", allOf(
            containsString("default-src 'self'"),
            containsString("script-src 'self'"),
            containsString("img-src 'self' data:"),
            containsString("connect-src 'self' ws:"),
            not(containsString("unsafe-inline")),
            not(containsString("unsafe-eval")))))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"));
  }

  @Test
  void cspEnabledSendsStrictPolicyOnAuthenticatedEndpoint() throws Exception {
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Security-Policy",
            SecurityConfig.CONTENT_SECURITY_POLICY));
  }
}
