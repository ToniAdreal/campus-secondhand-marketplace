package com.toni.marketplace.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.payouts.*}: tuning knobs for the seller-payout sweep
 * (backlog #406, step A1 unblocking the #398 payout e2e flow).
 *
 * <ul>
 *   <li>{@code sweep-cron}: how often the payout sweep
 *   ({@link SellerPayoutService#runScheduledSweep}) runs. Default hourly
 *   ({@code 0 0 * * * *}), unchanged by this item — production keeps the
 *   hourly cadence. The e2e stack overrides it to every 10 seconds via
 *   {@code APP_PAYOUTS_SWEEP_CRON} (see {@code frontend/playwright.config.ts})
 *   so a freshly completed order's payout can reach PAYABLE inside a
 *   60-second test; the override lives only in that throwaway stack.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.payouts")
public class PayoutProperties {

  /** Cron for the seller-payout sweep; six-field Spring cron, seconds first. */
  private String sweepCron = "0 0 * * * *";

  public String getSweepCron() {
    return sweepCron;
  }

  public void setSweepCron(String sweepCron) {
    this.sweepCron = sweepCron;
  }
}
