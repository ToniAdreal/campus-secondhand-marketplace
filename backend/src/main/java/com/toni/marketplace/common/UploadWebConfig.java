package com.toni.marketplace.common;

import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves uploaded listing photos statically under {@code /uploads/**} from
 * the local upload directory ({@code app.uploads.dir}; see
 * {@link com.toni.marketplace.item.ImageStorageService}). The endpoint is
 * public — listing photos are meant to be viewable by any visitor — so the
 * security config permits {@code /uploads/**} without a Bearer token.
 *
 * <p><b>Cache policy (backlog #176):</b> every response from this mapping
 * carries {@code Cache-Control: public, max-age=31536000, immutable}
 * (one year). That is safe only because of the storage guarantee in
 * {@code ImageStorageService}: a stored file's name is a fresh UUID and
 * its bytes never change under that name — replacing a photo mints a new
 * UUID name and deletes the old file, deleting a photo removes its name,
 * and a gallery reorder never touches file bytes. A cached copy can
 * therefore never go stale under a name a browser already holds; the old
 * name simply stops resolving (404).
 *
 * <p>Honest boundary: the policy applies to {@code /uploads/**} only.
 * API JSON responses are authenticated, per-user data and deliberately
 * gain no caching from this change — they keep the Spring Security
 * {@code no-cache, no-store, max-age=0, must-revalidate} policy, pinned
 * by {@code UploadCacheHeadersTest}.
 */
@Configuration
public class UploadWebConfig implements WebMvcConfigurer {

  /** One year in seconds: the max-age of the immutable upload policy. */
  public static final long UPLOAD_MAX_AGE_SECONDS = TimeUnit.DAYS.toSeconds(365);

  private final String uploadDir;

  public UploadWebConfig(@Value("${app.uploads.dir}") String uploadDir) {
    this.uploadDir = uploadDir;
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/uploads/**")
        .addResourceLocations("file:" + Paths.get(uploadDir).toAbsolutePath() + "/")
        .setCacheControl(CacheControl
            .maxAge(UPLOAD_MAX_AGE_SECONDS, TimeUnit.SECONDS)
            .cachePublic()
            .immutable());
  }
}
