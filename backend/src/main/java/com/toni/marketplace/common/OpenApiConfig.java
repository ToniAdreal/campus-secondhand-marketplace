package com.toni.marketplace.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI contract (backlog #95): springdoc generates {@code /v3/api-docs}
 * and the Swagger UI from the controllers, replacing prose-only documentation
 * of the {@code {code,message,data}} envelope ({@link ApiResponse}).
 *
 * <p>Access decision (documented in SecurityConfig): the docs are behind the
 * same Bearer authentication as {@code /actuator/metrics} — the route map is
 * not public information for this demo's threat model. No global security
 * requirement is declared here because {@code /api/auth/**} is mostly public;
 * the {@code bearerAuth} scheme is declared so clients can authorize in the
 * UI, and each operation's security follows the filter chain.
 *
 * <p>Honest scope: this is a portfolio/reconstruction project, not a
 * production system; the contract describes the mock-PSP API as implemented.
 */
@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI marketplaceOpenApi() {
    return new OpenAPI()
        .info(new Info()
            .title("Campus Second-hand Marketplace API")
            .version("0.1.0")
            .description("Every response uses the {code, message, data} envelope "
                + "(ApiResponse): code 0 on success, otherwise the HTTP status. "
                + "Payments are a mock PSP — no real money moves. "
                + "Portfolio/reconstruction project, not a production system."))
        .schemaRequirement("bearerAuth", new SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .description("Access token from POST /api/auth/login (15 min TTL); "
                + "the refresh token travels only in the httpOnly refresh_token cookie."));
  }
}
