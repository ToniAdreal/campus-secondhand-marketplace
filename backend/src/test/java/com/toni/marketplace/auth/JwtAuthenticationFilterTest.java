package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Pure unit tests for {@link JwtAuthenticationFilter}: no Spring context.
 * The chain and the filter-chain ordering are covered by
 * {@link SecurityChainTest}.
 */
class JwtAuthenticationFilterTest {

  private JwtTokenService jwt;
  private JwtAuthenticationFilter filter;
  private FilterChain chain;

  @BeforeEach
  void setUp() {
    jwt = mock(JwtTokenService.class);
    filter = new JwtAuthenticationFilter(jwt);
    chain = mock(FilterChain.class);
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private MockHttpServletRequest requestWith(String authHeader) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/items");
    if (authHeader != null) {
      request.addHeader(HttpHeaders.AUTHORIZATION, authHeader);
    }
    return request;
  }

  @Test
  void validTokenInstallsAuthenticationWithRoleAuthorities() throws Exception {
    when(jwt.parseAccessToken("good-token"))
        .thenReturn(new JwtTokenService.AccessClaims(42L, "alice", List.of("USER")));

    filter.doFilter(requestWith("Bearer good-token"), new MockHttpServletResponse(), chain);

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNotNull();
    assertThat(auth.getPrincipal()).isEqualTo(42L);
    assertThat(auth.getAuthorities())
        .extracting(Object::toString)
        .containsExactly("ROLE_USER");
    assertThat(auth.isAuthenticated()).isTrue();
  }

  @Test
  void multipleRolesMapToMultipleRoleAuthorities() throws Exception {
    when(jwt.parseAccessToken("admin-token"))
        .thenReturn(new JwtTokenService.AccessClaims(7L, "root", List.of("ADMIN", "USER")));

    filter.doFilter(requestWith("Bearer admin-token"), new MockHttpServletResponse(), chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting(Object::toString)
        .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
  }

  @Test
  void invalidTokenLeavesRequestUnauthenticated() throws Exception {
    when(jwt.parseAccessToken("bad-token")).thenThrow(new InvalidTokenException("nope"));

    filter.doFilter(requestWith("Bearer bad-token"), new MockHttpServletResponse(), chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void missingHeaderSkipsTokenValidationEntirely() throws Exception {
    filter.doFilter(requestWith(null), new MockHttpServletResponse(), chain);

    verifyNoInteractions(jwt);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void nonBearerHeaderIsIgnored() throws Exception {
    filter.doFilter(requestWith("Basic abc123"), new MockHttpServletResponse(), chain);

    verifyNoInteractions(jwt);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
}
