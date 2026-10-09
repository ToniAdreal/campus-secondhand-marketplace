package com.toni.marketplace.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * OpenAPI contract (backlog #95): {@code /v3/api-docs} and the Swagger UI sit
 * behind the same Bearer authentication as {@code /actuator/metrics} (the
 * route map is not public), and the generated document lists the main
 * controller paths plus the non-inferrable response codes annotated on the
 * order lifecycle (402 declined capture, 409, 422) and login (202 challenge,
 * 423 lockout).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpenApiContractTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private String accessToken;

  @BeforeEach
  void createUserAndToken() {
    User user = users.save(new User("openapi-reader", "openapi-reader@example.com",
        passwords.encode("s3cret-password")));
    accessToken = jwt.createTokenPair(user).accessToken();
  }

  @Test
  void apiDocsIsBehindAuthForAnonymous() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void swaggerUiIsBehindAuthForAnonymous() throws Exception {
    mockMvc.perform(get("/swagger-ui/index.html"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void apiDocsListsMainPathsWithBearerToken() throws Exception {
    mockMvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.openapi").value(startsWith("3.")))
        .andExpect(jsonPath("$.info.title").value("Campus Second-hand Marketplace API"))
        .andExpect(jsonPath("$.paths['/api/auth/login']").exists())
        .andExpect(jsonPath("$.paths['/api/auth/register']").exists())
        .andExpect(jsonPath("$.paths['/api/items']").exists())
        .andExpect(jsonPath("$.paths['/api/orders']").exists())
        .andExpect(jsonPath("$.paths['/api/orders/{id}/pay']").exists())
        .andExpect(jsonPath("$.paths['/api/messages']").exists())
        .andExpect(jsonPath("$.paths['/api/categories']").exists())
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
  }

  @Test
  void apiDocsCarriesAnnotatedNonInferrableCodes() throws Exception {
    mockMvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/orders/{id}/pay'].post.responses['402']").exists())
        .andExpect(jsonPath("$.paths['/api/orders/{id}/pay'].post.responses['409']").exists())
        .andExpect(jsonPath("$.paths['/api/orders/{id}/complete'].post.responses['422']").exists())
        .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['202']").exists())
        .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['423']").exists())
        .andExpect(content().string(containsString("bearerAuth")));
  }
}
