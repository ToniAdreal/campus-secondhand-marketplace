package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import com.toni.marketplace.common.InvalidTokenException;

/**
 * Pure unit tests for {@link JwtAuthenticationFilter}: no Spring context.
 * The chain and the filter-chain ordering are covered by
 * {@link SecurityChainTest}.
 */
class JwtAuthenticationFilterTest {

  private JwtTokenService jwt;
  private UserRepository users;
  private JwtAuthenticationFilter filter;
  private FilterChain chain;

  @BeforeEach
  void setUp() {
    jwt = mock(JwtTokenService.class);
    users = mock(UserRepository.class);
    filter = new JwtAuthenticationFilter(jwt, users);
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

  private User userWithVersion(long version) {
    User user = new User("alice", "alice@example.com", "$2a$12$hashed");
    ReflectionTestUtils.setField(user, "id", 42L);
    user.setTokenVersion(version);
    return user;
  }

  @Test
  void validTokenInstallsAuthenticationWithRoleAuthorities() throws Exception {
    when(jwt.parseAccessToken("good-token"))
        .thenReturn(new JwtTokenService.AccessClaims(42L, "alice", List.of("USER"), 0L));
    when(users.findById(42L)).thenReturn(Optional.of(userWithVersion(0)));

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
        .thenReturn(new JwtTokenService.AccessClaims(7L, "root", List.of("ADMIN", "USER"), 0L));
    User root = new User("root", "root@example.com", "$2a$12$hashed");
    ReflectionTestUtils.setField(root, "id", 7L);
    when(users.findById(7L)).thenReturn(Optional.of(root));

    filter.doFilter(requestWith("Bearer admin-token"), new MockHttpServletResponse(), chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting(Object::toString)
        .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
  }

  @Test
  void staleTokenVersionLeavesRequestUnauthenticated() throws Exception {
    // The token was issued at version 0; a password change bumped the row
    // to 1 — the bearer token must die even though it is still unexpired.
    when(jwt.parseAccessToken("stale-token"))
        .thenReturn(new JwtTokenService.AccessClaims(42L, "alice", List.of("USER"), 0L));
    when(users.findById(42L)).thenReturn(Optional.of(userWithVersion(1)));

    filter.doFilter(requestWith("Bearer stale-token"), new MockHttpServletResponse(), chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void tokenForDeletedUserLeavesRequestUnauthenticated() throws Exception {
    when(jwt.parseAccessToken("orphan-token"))
        .thenReturn(new JwtTokenService.AccessClaims(42L, "alice", List.of("USER"), 0L));
    when(users.findById(42L)).thenReturn(Optional.empty());

    filter.doFilter(requestWith("Bearer orphan-token"), new MockHttpServletResponse(), chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
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
