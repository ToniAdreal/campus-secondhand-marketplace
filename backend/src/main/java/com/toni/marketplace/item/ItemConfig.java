package com.toni.marketplace.item;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code app.listings.*} (backlog #177). Scheduling itself is
 * enabled once for the whole application in
 * {@link com.toni.marketplace.job.CleanupConfig}; this config only owns
 * the item-domain property binding, mirroring {@code OrderConfig} /
 * {@code OfferConfig}.
 */
@Configuration
@EnableConfigurationProperties(ListingExpiryProperties.class)
public class ItemConfig {
}
