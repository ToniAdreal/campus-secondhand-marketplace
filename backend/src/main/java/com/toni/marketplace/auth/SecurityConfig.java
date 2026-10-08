package com.toni.marketplace.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.common.ApiResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless security: no sessions, no CSRF tokens (the API is consumed by the
 * React SPA and native clients, not browser forms).
 *
 * <ul>
 *   <li>{@code /api/auth/**} is public (register/login/refresh land here),
 *       except {@code POST /api/auth/logout}, {@code POST /api/auth/password}
 *       and {@code GET /api/auth/me},
 *       which need a Bearer access token because they act on the caller's own
 *       session.</li>
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
public class SecurityConfig {

  @Bean
  public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenService jwt) {
    return new JwtAuthenticationFilter(jwt);
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
                                                 AccessDeniedHandler accessDeniedHandler)
      throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            // Logout, password change and session restore need the caller's
            // identity: a valid Bearer access token. The rest of /api/auth/**
            // stays public.
            .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
            .requestMatchers(HttpMethod.POST, "/api/auth/password").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
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
}
