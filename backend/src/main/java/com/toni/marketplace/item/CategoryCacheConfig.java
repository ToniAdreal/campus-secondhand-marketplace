package com.toni.marketplace.item;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * In-memory cache for the category taxonomy (backlog #87).
 *
 * <p>The taxonomy is tiny (seeded by Flyway V3, changed only through the
 * ADMIN endpoints) and read on every browse render, so it is the one read
 * in this project that is worth caching. The manager is Spring's built-in
 * {@link ConcurrentMapCacheManager} from spring-context — no new
 * dependency and no external cache server. Honest limits: the cache is
 * JVM-local (a multi-instance deployment would need a shared cache or a
 * TTL, neither of which this demo has) and entries never expire on their
 * own — freshness comes entirely from the {@code @CacheEvict} hooks on
 * the ADMIN write paths in {@link CategoryService}.
 */
@Configuration
@EnableCaching
public class CategoryCacheConfig {

  /** The single cache region: the whole taxonomy under one key. */
  public static final String CATEGORIES_CACHE = "categories";

  @Bean
  CacheManager cacheManager() {
    return new ConcurrentMapCacheManager(CATEGORIES_CACHE);
  }
}
