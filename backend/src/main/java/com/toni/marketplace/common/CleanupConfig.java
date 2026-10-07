package com.toni.marketplace.common;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduler used by maintenance jobs (currently the nightly
 * stale-data purge in {@link DataRetentionService}) and binds
 * {@code app.cleanup.*}.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(CleanupProperties.class)
public class CleanupConfig {
}
