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
 *       {@code GET /api/auth/me}, {@code GET /api/auth/sessions} and
 *       {@code DELETE /api/auth/sessions/**},
 *       which need a Bearer access token because they act on the caller's own
 *       session(s).</li>
 *   <li>{@code /uploads/**} is public — listing photos are meant to be
 *       viewable by any visitor.</li>
 *   <li>{@code /actuator/health} and {@code /actuator/info} are public —
 *       unauthenticated liveness/readiness probes for the compose stack; every
 *       other actuator endpoint ({@code /actuator/**}, currently
 *       {@code /actuator/metrics} and {@code /actuator/prometheus}) needs a
 *       Bearer access token. {@code /actuator/prometheus} exports the Micrometer
 *       registry in Prometheus text format (scrape target for an external
 *       Prometheus server); alerting rules are not configured (follow-up).</li>
 *   <li>{@code /error} is public so exception-driven error pages render.</li>
 *   <li>Everything else requires a valid Bearer access token.</li>
 *   <li>CORS is an explicit allowlist ({@code app.cors.allowed-origins}):
 *       cross-origin calls are honored only from listed origins; credentials
 *       (the httpOnly refresh cookie) are never combined with a wildcard.</li>
 *   <li>Every response carries {@code X-Content-Type-Options: nosniff},
 *       {@code X-Frame-Options: DENY} and {@code Referrer-Policy:
 *       no-referrer}; {@code Strict-Transport-Security} is opt-in via
 *       {@code app.security.headers.hsts-enabled} for HTTPS deployments
 *       (off by default for local plain-HTTP dev). No CSP yet.</li>
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
        .headers(headers -> headers
            // Security response headers (backlog #64). Spring Security's
            // defaults are kept where they match the intent: nosniff and
            // DENY framing; Referrer-Policy is not in the defaults so it is
            // declared explicitly. HSTS is opt-in via
            // app.security.headers.hsts-enabled — it stays off on local
            // plain-HTTP dev and is enabled for HTTPS deployments.
            // No Content-Security-Policy yet: a strict CSP that doesn't
            // break the SPA's inline scripts is a declared follow-up.
            .contentTypeOptions(Customizer.withDefaults())
            .frameOptions(frame -> frame.deny())
            .referrerPolicy(ref -> ref.policy(ReferrerPolicy.NO_REFERRER))
            .httpStrictTransportSecurity(hsts -> {
              if (securityHeaders.isHstsEnabled()) {
                hsts.maxAgeInSeconds(31536000).includeSubDomains(true);
              } else {
                hsts.disable();
              }
            }))
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            // Logout, password change, session restore, per-session
            // management and 2FA enrollment need the caller's identity: a
            // valid Bearer access token. The rest of /api/auth/** stays
            // public — including /api/auth/2fa/authenticate, the
            // unauthenticated second-factor exchange for the 202 challenge.
            .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
            .requestMatchers(HttpMethod.POST, "/api/auth/password").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/auth/sessions").authenticated()
            .requestMatchers(HttpMethod.DELETE, "/api/auth/sessions/**").authenticated()
            .requestMatchers(HttpMethod.POST, "/api/auth/2fa/setup").authenticated()
            .requestMatchers(HttpMethod.POST, "/api/auth/2fa/enable").authenticated()
            .requestMatchers("/api/auth/**", "/uploads/**", "/error").permitAll()
            // Actuator: public liveness/readiness probes, everything else
            // authenticated. Matcher order matters — the more specific
            // health/info matchers come first.
            .requestMatchers("/actuator/health", "/actuator/info").permitAll()
            .requestMatchers("/actuator/**").authenticated()
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
   * exposed so the frontend's error handling can read them.
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(props.getAllowedOrigins());
    config.setAllowCredentials(true);
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of(
        "Authorization", "Content-Type", "X-Request-ID", "Idempotency-Key", "X-Requested-With"));
    config.setExposedHeaders(List.of("X-Request-ID", "Retry-After"));
    config.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
