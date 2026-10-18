package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Session-limit defaults (backlog #191): the documented production
 * default is 5 live sessions per user; the MockMvc classes pin the
 * bound value at 3 and at 1 via {@code @TestPropertySource}.
 */
class SessionLimitPropertiesTest {

  @Test
  void defaultCapIsFive() {
    assertThat(new SessionLimitProperties().getMaxSessionsPerUser()).isEqualTo(5);
  }

  @Test
  void capRoundTripsThroughTheSetter() {
    SessionLimitProperties props = new SessionLimitProperties();
    props.setMaxSessionsPerUser(2);
    assertThat(props.getMaxSessionsPerUser()).isEqualTo(2);
  }
}
