package com.toni.marketplace.dispute;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code app.disputes.*}, mirroring {@code OfferConfig} /
 * {@code OrderConfig}: this config only owns the dispute-domain
 * property binding.
 */
@Configuration
@EnableConfigurationProperties(DisputeProperties.class)
public class DisputeConfig {
}
