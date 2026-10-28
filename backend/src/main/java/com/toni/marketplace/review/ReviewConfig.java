package com.toni.marketplace.review;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code app.reviews.*} (backlog #254), mirroring
 * {@code MessageConfig}: this config only owns the review-domain
 * property binding.
 */
@Configuration
@EnableConfigurationProperties(ReviewProperties.class)
public class ReviewConfig {
}
