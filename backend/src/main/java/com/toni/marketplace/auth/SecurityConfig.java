package com.toni.marketplace.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.common.ApiResponse;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless security: no sessions, no CSRF tokens (the API is consumed by the
 * React SPA and native clients, not browser forms).
 *
 * <ul>
 *   <li>{@code /api/auth/**} is public (register/login/refresh land here),
 *       except {@code POST /api/auth/logout}, {@code POST /api/auth/password},
 *       {@code POST /api/auth/email-change},
 *       {@code GET /api/auth/me}, {@code DELETE /api/auth/me} (account
 *       closure, backlog #158), {@code GET /api/auth/sessions},
 *       {@code DELETE /api/auth/sessions/**},
 *       {@code POST /api/auth/sessions/revoke-others} (backlog #163),
 *       {@code GET /api/auth/login-history} (backlog #171) and
 *       {@code GET /api/auth/trusted-devices} +
 *       {@code DELETE /api/auth/trusted-devices/**} (backlog #199),
 *       which need a Bearer access token because they act on the caller's own
 *       session(s) / sign-in history.</li>
 *   <li>{@code /uploads/**} is public — listing photos are meant to be
 *       viewable by any visitor.</li>
 *   <li>{@code /actuator/health}, its {@code liveness} / {@code readiness}
 *       groups (backlog #128) and {@code /actuator/info} are public —
 *       unauthenticated liveness/readiness probes for the compose stack; every
 *       other actuator endpoint ({@code /actuator/**}, currently
 *       {@code /actuator/metrics} and {@code /actuator/prometheus}) needs a
 *       Bearer access token. {@code /actuator/prometheus} exports the Micrometer
 *       registry in Prometheus text format (scrape target for an external
 *       Prometheus server); alert rules are provided in docs/alert-rules.yml (loaded by the
 *       operator's own Prometheus; no Alertmanager is bundled).</li>
 *   <li>{@code /error} is public so exception-driven error pages render.</li>
 *   <li>Everything else requires a valid Bearer access token.</li>
 *   <li>CORS is an explicit allowlist ({@code app.cors.allowed-origins}):
 *       cross-origin calls are honored only from listed origins; credentials
 *       (the httpOnly refresh cookie) are never combined with a wildcard.</li>
 *   <li>Every response carries {@code X-Content-Type-Options: nosniff},
 *       {@code X-Frame-Options: DENY} and {@code Referrer-Policy:
 *       no-referrer}; {@code Strict-Transport-Security} is opt-in via
 *       {@code app.security.headers.hsts-enabled} for HTTPS deployments
 *       (off by default for local plain-HTTP dev).
 *       {@code Content-Security-Policy} is likewise opt-in via
 *       {@code app.security.headers.csp-enabled} (off by default); when on,
 *       the strict policy in {@link #CONTENT_SECURITY_POLICY} is sent —
 *       {@code 'self'} everywhere, no {@code 'unsafe-inline'}. With
 *       {@code app.security.headers.csp-report-enabled} also on
 *       (backlog #204), the policy gains a {@code report-uri} pointing
 *       at the public {@code POST /api/security/csp-report} intake and
 *       responses carry a {@code Reporting-Endpoints} header.</li>
 *   <li>Unauthenticated/expired requests get a JSON {@code 401} in the
 *       project's {@code {code,message,data}} envelope, never a redirect or
 *       the Spring default HTML error page.</li>
 *   <li>Denied authorization (authenticated but insufficient role) gets a JSON
 *       {@code 403} in the same envelope.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // enables @PreAuthorize on controller methods
@EnableConfigurationProperties({CorsProperties.class, SecurityHeadersProperties.class})
public class SecurityConfig {

  /**
   * Content-Security-Policy sent when {@code app.security.headers.csp-enabled}
   * is on (backlog #80).
   *
   * <ul>
   *   <li>{@code default-src 'self'} — the fallback for every fetch
   *       directive not listed (styles and fonts included: the built SPA
   *       loads one external stylesheet, no webfonts, no inline styles).</li>
   *   <li>{@code script-src 'self'} — external bundles only. Verified
   *       against the real Vite build: {@code dist/index.html} references
   *       only {@code /assets/*.js}; the source has no {@code eval},
   *       inline handlers or inline {@code <script>} blocks, so no
   *       {@code 'unsafe-inline'} / {@code 'unsafe-eval'} is needed.</li>
   *   <li>{@code img-src 'self' data:} — {@code 'self'} covers listing
   *       photos under {@code /uploads/**}; {@code data:} covers any
   *       data-URI image.</li>
   *   <li>{@code connect-src 'self' ws:} — API calls stay same-origin;
   *       {@code ws:} exists only for the Vite dev server's HMR socket
   *       and is inert in the production build (the bundled app opens no
   *       WebSocket — messaging is offline REST, no live channel).</li>
   * </ul>
   *
   * <p>Honest scope: this is an API response header — in the compose
   * stack nginx serves the SPA itself, so a deployment that wants the
   * policy on HTML documents must mirror it at the proxy; the flag here
   * covers everything the backend serves (API + {@code /uploads/**}).
   */
  static final String CONTENT_SECURITY_POLICY =
      "default-src 'self'; img-src 'self' data:; script-src 'self'; connect-src 'self' ws:";

  /**
   * Public CSP violation report intake (backlog #204). When both
   * {@code csp-enabled} and {@code csp-report-enabled} are on, the
   * policy gains {@code ; report-uri /api/security/csp-report} (the
   * legacy channel) and responses carry the {@code Reporting-Endpoints}
   * header below (the Reporting API channel) — with either flag off the
   * policy stays byte-identical to the backlog #80 string above.
   */
  static final String CSP_REPORT_PATH = "/api/security/csp-report";

  /** Value of the {@code Reporting-Endpoints} header (backlog #204). */
  static final String REPORTING_ENDPOINTS_VALUE = "csp-endpoint=\"/api/security/csp-report\"";

  @Bean
  public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenService jwt, UserRepository users) {
    return new JwtAuthenticationFilter(jwt, users);
  }

  @Bean
  public AuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper mapper) {
    return (request, response, authException) -> {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding("UTF-8");
      mapper.writeValue(response.getWriter(),
          ApiResponse.fail(HttpStatus.UNAUTHORIZED.value(), "unauthorized"));
    };
  }

  @Bean
  public AccessDeniedHandler restAccessDeniedHandler(ObjectMapper mapper) {
    // Safety net for denied requests that reach the filter layer (method
    // security is AOP-based and usually surfaces in GlobalExceptionHandler
    // instead — see its AccessDeniedException handler). Both paths return
    // the same JSON 403 envelope: no HTML, no redirects.
    return (request, response, accessDeniedException) -> {
      response.setStatus(HttpStatus.FORBIDDEN.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding("UTF-8");
      mapper.writeValue(response.getWriter(),
          ApiResponse.fail(HttpStatus.FORBIDDEN.value(), "forbidden"));
    };
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                 JwtAuthenticationFilter jwtFilter,
                                                 AuthenticationEntryPoint entryPoint,
                                                 AccessDeniedHandler accessDeniedHandler,
                                                 CorsConfigurationSource corsConfigurationSource,
                                                 SecurityHeadersProperties securityHeaders)
      throws Exception {
    http
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        .csrf(AbstractHttpConfigurer::disable)
        .headers(headers -> {
          headers
              // Security response headers (backlog #64). Spring Security's
              // defaults are kept where they match the intent: nosniff and
              // DENY framing; Referrer-Policy is not in the defaults so it is
              // declared explicitly. HSTS is opt-in via
              // app.security.headers.hsts-enabled — it stays off on local
              // plain-HTTP dev and is enabled for HTTPS deployments.
              .contentTypeOptions(Customizer.withDefaults())
              .frameOptions(frame -> frame.deny())
              .referrerPolicy(ref -> ref.policy(ReferrerPolicy.NO_REFERRER))
              .httpStrictTransportSecurity(hsts -> {
                if (securityHeaders.isHstsEnabled()) {
                  hsts.maxAgeInSeconds(31536000).includeSubDomains(true);
                } else {
                  hsts.disable();
                }
              });
          // Content-Security-Policy (backlog #80): opt-in via
          // app.security.headers.csp-enabled, off by default like HSTS.
          // The CSP configurer has no disable() switch, so it is only
          // configured when the flag is on — an unconfigured chain sends
          // no CSP header at all.
          if (securityHeaders.isCspEnabled()) {
            // CSP violation reporting (backlog #204): only when the
            // report endpoint is also enabled does the policy gain the
            // report-uri directive and the Reporting-Endpoints header
            // for Reporting-API browsers; otherwise the policy is the
            // byte-identical #80 string.
            boolean reportEnabled = securityHeaders.isCspReportEnabled();
            String policy = reportEnabled
                ? CONTENT_SECURITY_POLICY + "; report-uri " + CSP_REPORT_PATH
                : CONTENT_SECURITY_POLICY;
            headers.contentSecurityPolicy(csp -> csp.policyDirectives(policy));
            if (reportEnabled) {
              headers.addHeaderWriter((request, response) ->
                  response.setHeader("Reporting-Endpoints", REPORTING_ENDPOINTS_VALUE));
            }
          }
        })
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            // Logout, password change, session restore, per-session
            // management and 2FA enrollment need the caller's identity: a
            // valid Bearer access token. The rest of /api/auth/** stays
            // public — including /api/auth/2fa/authenticate, the
            // unauthenticated second-factor exchange for the 202 challenge.
            .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
            .requestMatchers(HttpMethod.POST, "/api/auth/password").authenticated()
            .requestMatchers(HttpMethod.POST, "/api/auth/email-change").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
            .requestMatchers(HttpMethod.DELETE, "/api/auth/me").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/auth/sessions").authenticated()
            .requestMatchers(HttpMethod.DELETE, "/api/auth/sessions/**").authenticated()
            .requestMatchers(HttpMethod.POST, "/api/auth/sessions/revoke-others").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/auth/login-history").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/auth/trusted-devices").authenticated()
            .requestMatchers(HttpMethod.DELETE, "/api/auth/trusted-devices/**").authenticated()
            .requestMatchers(HttpMethod.POST, "/api/auth/2fa/setup").authenticated()
            .requestMatchers(HttpMethod.POST, "/api/auth/2fa/enable").authenticated()
            .requestMatchers(HttpMethod.POST, "/api/auth/2fa/disable").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/auth/2fa/recovery-codes/count").authenticated()
            .requestMatchers(HttpMethod.POST, "/api/auth/2fa/recovery-codes/regenerate").authenticated()
            .requestMatchers("/api/auth/**", "/uploads/**", "/error").permitAll()
            // Mock-PSP webhook (backlog #140): public route, authenticated
            // by its HMAC signature header instead of a Bearer token (the
            // service verifies it before parsing anything).
            .requestMatchers(HttpMethod.POST, "/api/payments/webhooks/mock-psp").permitAll()
            // CSP violation report intake (backlog #204): public route —
            // browsers send reports automatically and cannot present a
            // Bearer token. Defended by the body cap in the controller,
            // its own per-IP bucket in AuthRateLimitFilter, and the
            // service logging/counting only sanitized fields.
            .requestMatchers(HttpMethod.POST, "/api/security/csp-report").permitAll()
            // Notification SSE stream (backlog #203): public at the chain
            // because EventSource cannot send an Authorization header —
            // the controller consumes a one-time, 60-second ticket issued
            // by the authenticated POST /api/notifications/stream-ticket,
            // and a missing/reused/expired ticket answers the same 401
            // envelope. The access token is never accepted in a URL.
            .requestMatchers(HttpMethod.GET, "/api/notifications/stream").permitAll()
            // Actuator: public liveness/readiness probes, everything else
            // authenticated. Matcher order matters — the more specific
            // health/info matchers come first.
            .requestMatchers("/actuator/health", "/actuator/health/liveness",
                "/actuator/health/readiness", "/actuator/info").permitAll()
            .requestMatchers("/actuator/**").authenticated()
            // OpenAPI docs (backlog #95): same rule as actuator metrics —
            // authenticated, not public; the route map is not public
            // information for this demo's threat model.
            .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").authenticated()
            .anyRequest().authenticated())
        .exceptionHandling(eh -> eh
            .authenticationEntryPoint(entryPoint)
            .accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  /**
   * Explicit CORS allowlist (backlog #63). No wildcard origins: credentials
   * (the httpOnly refresh cookie) are allowed only for the origins listed in
   * {@link CorsProperties}. Allowed methods/headers cover what the API and
   * the SPA actually use; {@code X-Request-ID} and {@code Retry-After} are
   * exposed so the frontend's error handling can read them, and
   * {@code Content-Disposition} is exposed so the data-export download
   * (#172) can read the server filename cross-origin — without it the
   * browser hides the header and the SPA would save under a fallback
   * name instead of the server's.
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(props.getAllowedOrigins());
    config.setAllowCredentials(true);
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of(
        "Authorization", "Content-Type", "X-Request-ID", "Idempotency-Key", "X-Requested-With"));
    config.setExposedHeaders(List.of("X-Request-ID", "Retry-After", "Content-Disposition"));
    config.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
