package com.toni.marketplace.common;

import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves uploaded listing photos statically under {@code /uploads/**} from
 * the local upload directory ({@code app.uploads.dir}; see
 * {@link com.toni.marketplace.item.ImageStorageService}). The endpoint is
 * public — listing photos are meant to be viewable by any visitor — so the
 * security config permits {@code /uploads/**} without a Bearer token.
 */
@Configuration
public class UploadWebConfig implements WebMvcConfigurer {

  private final String uploadDir;

  public UploadWebConfig(@Value("${app.uploads.dir}") String uploadDir) {
    this.uploadDir = uploadDir;
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/uploads/**")
        .addResourceLocations("file:" + Paths.get(uploadDir).toAbsolutePath() + "/")
        .setCachePeriod(3600);
  }
}
