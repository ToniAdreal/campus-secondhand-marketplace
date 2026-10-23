package com.toni.marketplace.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stateless JWT authentication: extracts the Bearer access token, validates it
 * with {@link JwtTokenService}, and installs the authentication into the
 * {@link SecurityContextHolder}.
 *
 * <ul>
 *   <li>No token present → request continues unauthenticated (the security
 *       filter chain's entry point answers 401 with the JSON envelope).</li>
 *   <li>Invalid/expired/wrong-type token → also left unauthenticated; same 401.</li>
 *   <li>Stale token → the {@code tver} claim disagrees with the user's
 *       {@code token_version} row (password was changed, reset or the user
 *       logged out — backlog #148 — after the token was issued) →
 *       unauthenticated, same 401. The row lookup costs one extra
 *       query per authenticated request; that is the price of server-side
 *       bearer revocation without a token denylist.</li>
 *   <li>Disabled account (backlog #88) → the user's {@code disabled} flag
 *       is set by an ADMIN → unauthenticated, same 401, whatever the token
 *       claims. (Disable also bumps {@code token_version}, so the token
 *       stays dead even if the account is later re-enabled.)</li>
 *   <li>Token for a user row that no longer exists → unauthenticated.</li>
 *   <li>Roles from the token are mapped to {@code ROLE_<name>} authorities so
 *       that method-level {@code @PreAuthorize("hasRole('ADMIN')")} works.</li>
 * </ul>
 *
 * The principal is the numeric user id ({@link Long}); controllers that need
 * the current user can read {@code SecurityContextHolder.getContext()
 * .getAuthentication().getPrincipal()}.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtTokenService jwt;
  private final UserRepository users;
  private final CurrentAccessClaims currentClaims;

  public JwtAuthenticationFilter(JwtTokenService jwt, UserRepository users,
                                 CurrentAccessClaims currentClaims) {
    this.jwt = jwt;
    this.users = users;
    this.currentClaims = currentClaims;
  }

  /** Legacy constructor for direct unit tests without a request-scoped holder. */
  public JwtAuthenticationFilter(JwtTokenService jwt, UserRepository users) {
    this(jwt, users, null);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request,
                                  HttpServletResponse response,
                                  FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith(BEARER_PREFIX)) {
      String token = header.substring(BEARER_PREFIX.length()).trim();
      try {
        JwtTokenService.AccessClaims claims = jwt.parseAccessToken(token);
        // Server-side revocation check: a password change/reset, an ADMIN
        // disable or a logout bumps the row's tokenVersion, invalidating
        // every previously issued bearer token.
        if (isStale(claims)) {
          SecurityContextHolder.clearContext();
          clearClaims();
        } else {
          List<SimpleGrantedAuthority> authorities = claims.roles().stream()
              .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
              .toList();
          UsernamePasswordAuthenticationToken auth =
              new UsernamePasswordAuthenticationToken(claims.userId(), null, authorities);
          auth.setDetails(request.getRemoteAddr());
          SecurityContextHolder.getContext().setAuthentication(auth);
          // Backlog #218: the step-up gate reads the presented token's
          // auth-time from here — never the raw token.
          if (currentClaims != null) {
            currentClaims.set(claims);
          }
        }
      } catch (RuntimeException ignored) {
        // Malformed, expired, wrong signature or not an access token:
        // stay unauthenticated and let the entry point answer 401.
        SecurityContextHolder.clearContext();
        clearClaims();
      }
    }
    filterChain.doFilter(request, response);
  }

  private boolean isStale(JwtTokenService.AccessClaims claims) {
    return users.findById(claims.userId())
        .map(user -> user.isDisabled()
            || user.getTokenVersion() != claims.tokenVersion())
        .orElse(true);
  }

  private void clearClaims() {
    if (currentClaims != null) {
      currentClaims.clear();
    }
  }
}
