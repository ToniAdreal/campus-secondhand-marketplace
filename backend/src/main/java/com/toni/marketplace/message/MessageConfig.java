package com.toni.marketplace.message;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code app.messages.*} (backlog #206), mirroring
 * {@code OfferConfig} / {@code ItemConfig}: this config only owns the
 * message-domain property binding.
 */
@Configuration
@EnableConfigurationProperties(MessageProperties.class)
public class MessageConfig {
}
