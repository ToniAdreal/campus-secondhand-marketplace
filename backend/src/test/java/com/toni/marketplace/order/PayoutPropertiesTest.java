package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Payout-sweep cadence defaults (backlog #406): the production sweep
 * stays hourly; the e2e stack overrides it to every 10 seconds via
 * {@code APP_PAYOUTS_SWEEP_CRON} so a freshly completed order's payout
 * can reach PAYABLE inside a 60-second test.
 */
class PayoutPropertiesTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(PayoutBindingConfig.class);

  @Configuration
  @EnableConfigurationProperties(PayoutProperties.class)
  static class PayoutBindingConfig {
  }

  @Test
  void defaultSweepCronIsHourly() {
    assertThat(new PayoutProperties().getSweepCron()).isEqualTo("0 0 * * * *");
  }

  @Test
  void sweepCronRoundTripsThroughTheSetter() {
    PayoutProperties props = new PayoutProperties();
    props.setSweepCron("*/10 * * * * *");
    assertThat(props.getSweepCron()).isEqualTo("*/10 * * * * *");
  }

  @Test
  void bindsCustomCronFromProperty() {
    runner.withPropertyValues("app.payouts.sweep-cron=*/10 * * * * *")
        .run(context -> assertThat(context.getBean(PayoutProperties.class).getSweepCron())
            .isEqualTo("*/10 * * * * *"));
  }

  @Test
  void bindsDefaultCronWhenPropertyIsAbsent() {
    runner.run(context -> assertThat(context.getBean(PayoutProperties.class).getSweepCron())
        .isEqualTo("0 0 * * * *"));
  }
}
