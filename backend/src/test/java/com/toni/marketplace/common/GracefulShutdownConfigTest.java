package com.toni.marketplace.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.core.env.Environment;

/**
 * Graceful shutdown (backlog #136): a deploy or {@code docker compose down}
 * SIGTERMs the JVM, and the default abrupt shutdown would strand in-flight
 * pays/uploads mid-request. application.yml therefore sets
 * {@code server.shutdown=graceful} with a 30 s per-phase lifecycle timeout
 * (docker-compose pairs it with a 40 s stop_grace_period). These assertions
 * pin the values as BOUND from application.yml — ServerProperties is what
 * the embedded web server actually reads — so a future edit cannot silently
 * drop the graceful mode. Honest scope: this drains HTTP requests only;
 * the @Scheduled sweeps are not transactional with shutdown and stay
 * idempotent/batch-tolerant as designed (#35/#51).
 */
@SpringBootTest
class GracefulShutdownConfigTest {

  @Autowired
  private ServerProperties serverProperties;

  @Autowired
  private Environment environment;

  @Test
  void serverShutdownModeIsBoundToGraceful() {
    assertThat(serverProperties.getShutdown()).isEqualTo(Shutdown.GRACEFUL);
  }

  @Test
  void lifecycleShutdownPhaseTimeoutIsBoundToThirtySeconds() {
    assertThat(environment.getProperty(
        "spring.lifecycle.timeout-per-shutdown-phase", Duration.class))
        .isEqualTo(Duration.ofSeconds(30));
  }
}
