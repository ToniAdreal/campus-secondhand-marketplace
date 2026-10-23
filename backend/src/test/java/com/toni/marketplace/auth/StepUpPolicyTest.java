package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toni.marketplace.common.StepUpRequiredException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link StepUpPolicy} (backlog #218).
 *
 * <p>The policy reads the current request's {@code auth_time} claim from the
 * request-scoped {@link CurrentAccessClaims} holder. Without an active
 * request scope (direct unit/service tests) the provider returns null and
 * the gate is a deliberate no-op — the HTTP layer (MockMvc tests in
 * {@code StepUpTest}) is the enforcement point.
 */
class StepUpPolicyTest {

  private StepUpProperties props;
  private MutableClock clock;

  @BeforeEach
  void setUp() {
    props = new StepUpProperties();
    clock = new MutableClock();
  }

  private StepUpPolicy policyWithHolder(Instant authTime) {
    CurrentAccessClaims holder = new CurrentAccessClaims();
    holder.set(new JwtTokenService.AccessClaims(1L, "u", List.of("USER"), 0L, authTime));
    @SuppressWarnings("unchecked")
    ObjectProvider<CurrentAccessClaims> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(holder);
    return new StepUpPolicy(props, clock, provider);
  }

  @Test
  void disabledPolicyIsAlwaysFresh() {
    assertThat(StepUpPolicy.disabled().isFresh()).isTrue();
  }

  @Test
  void noRequestScopeIsNoOp() {
    // Null provider = no request scope active (direct unit tests).
    StepUpPolicy policy = new StepUpPolicy(props, clock, null);
    assertThat(policy.isFresh()).isTrue();
    // And it must not throw either.
    policy.requireFresh();
  }

  @Test
  void freshProofPasses() {
    StepUpPolicy policy = policyWithHolder(clock.instant().minus(Duration.ofMinutes(9)));
    assertThat(policy.isFresh()).isTrue();
    policy.requireFresh();
  }

  @Test
  void staleProofThrowsStepUpRequired() {
    StepUpPolicy policy = policyWithHolder(clock.instant().minus(Duration.ofMinutes(11)));
    assertThat(policy.isFresh()).isFalse();
    assertThatThrownBy(policy::requireFresh)
        .isInstanceOf(StepUpRequiredException.class)
        .hasMessageContaining("re-authentication required");
  }

  @Test
  void proofExactlyMaxAgeOldIsStillFresh() {
    // Boundary inclusive: exactly maxAge old counts as fresh.
    StepUpPolicy policy = policyWithHolder(clock.instant().minus(Duration.ofMinutes(10)));
    assertThat(policy.isFresh()).isTrue();
    policy.requireFresh();
  }

  @Test
  void missingAuthTimeReadsAsStale() {
    // Pre-#218 tokens carry no `auth_time` claim (null) — they must never pass.
    StepUpPolicy policy = policyWithHolder(null);
    assertThat(policy.isFresh()).isFalse();
    assertThatThrownBy(policy::requireFresh)
        .isInstanceOf(StepUpRequiredException.class);
  }

  @Test
  void emptyHolderIsNoOp() {
    // No token presented on the request (direct service call outside
    // HTTP, e.g. TotpDisableRollbackTest's full-context transactions):
    // the gate must not fire — the security chain 401s before any
    // gated endpoint runs, so this is unreachable over HTTP.
    CurrentAccessClaims holder = new CurrentAccessClaims();
    @SuppressWarnings("unchecked")
    ObjectProvider<CurrentAccessClaims> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(holder);
    StepUpPolicy policy = new StepUpPolicy(props, clock, provider);
    assertThat(policy.isFresh()).isTrue();
    policy.requireFresh();
  }

  @Test
  void scopeResolutionFailureIsNoOp() {
    // No request scope active at all: resolving the holder throws.
    @SuppressWarnings("unchecked")
    ObjectProvider<CurrentAccessClaims> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenThrow(new IllegalStateException("no scope"));
    StepUpPolicy policy = new StepUpPolicy(props, clock, provider);
    assertThat(policy.isFresh()).isTrue();
    policy.requireFresh();
  }

  @Test
  void customMaxAgeIsHonored() {
    props.setMaxAge(Duration.ofMinutes(30));
    StepUpPolicy policy = policyWithHolder(clock.instant().minus(Duration.ofMinutes(20)));
    assertThat(policy.isFresh()).isTrue();
  }
}
