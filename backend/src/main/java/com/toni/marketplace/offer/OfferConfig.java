package com.toni.marketplace.offer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code app.offers.*}. Scheduling itself is enabled once for the
 * whole application in {@link com.toni.marketplace.job.CleanupConfig};
 * this config only owns the offer-domain property binding, mirroring
 * {@code OrderConfig}.
 */
@Configuration
@EnableConfigurationProperties(OfferProperties.class)
public class OfferConfig {
}
