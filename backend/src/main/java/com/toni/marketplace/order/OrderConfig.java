package com.toni.marketplace.order;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code app.orders.*}. Scheduling itself is enabled once for the whole
 * application in {@link com.toni.marketplace.common.CleanupConfig}; this
 * config only owns the order-domain property binding.
 */
@Configuration
@EnableConfigurationProperties(OrderExpiryProperties.class)
public class OrderConfig {
}
