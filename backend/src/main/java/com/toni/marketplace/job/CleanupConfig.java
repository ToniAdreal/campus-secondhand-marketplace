package com.toni.marketplace.job;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduler used by maintenance jobs (the nightly stale-data
 * purge in {@link DataRetentionService} and the orphan-upload sweep in
 * {@link OrphanUploadSweeper}) and binds {@code app.cleanup.*}. It is
 * also the seam every other domain's scheduled job hangs off: scheduling
 * is enabled here exactly once for the whole application, and the
 * single-instance guard's settings ({@code app.jobs.*}, backlog #215)
 * are bound here too, next to the jobs it guards.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({CleanupProperties.class, JobLockProperties.class})
public class CleanupConfig {
}
