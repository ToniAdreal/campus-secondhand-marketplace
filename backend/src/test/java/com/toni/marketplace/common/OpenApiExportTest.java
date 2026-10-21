package com.toni.marketplace.common;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * OpenAPI export for the frontend API-drift gate (backlog #205).
 *
 * <p>The drift script ({@code frontend/scripts/check-api-drift.mjs})
 * compares the frontend api modules' calls against the OpenAPI
 * document, so the document has to get from the backend's contract
 * surface to a file the node script can read. This test is that bridge
 * and nothing more: it fetches {@code /v3/api-docs} through the same
 * authenticated MockMvc path {@link OpenApiContractTest} pins, and
 * writes the response verbatim to the path named by the
 * {@code OPENAPI_EXPORT_PATH} environment variable.
 *
 * <p>The export is strictly opt-in: with the variable unset the test
 * aborts (JUnit assumption) and the normal suite is unchanged — no
 * file is written on a developer's machine or in the backend-unit CI
 * job. The {@code api-drift} CI job sets the variable and runs only
 * this test, then hands the file to {@code npm run check:api}. The
 * exported file is a build artifact and is gitignored; it is never
 * committed, so it can never go stale in the repo — every gate run
 * re-exports from the controllers as they are on that commit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpenApiExportTest {

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
    User user = users.save(new User("openapi-export", "openapi-export@example.com",
        passwords.encode("s3cret-password")));
    accessToken = jwt.createTokenPair(user).accessToken();
  }

  @Test
  void exportOpenApiDocumentWhenRequested() throws Exception {
    String target = System.getenv("OPENAPI_EXPORT_PATH");
    assumeTrue(target != null && !target.isBlank(),
        "OPENAPI_EXPORT_PATH not set — export is opt-in (backlog #205)");

    MvcResult result = mockMvc.perform(
            get("/v3/api-docs").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andReturn();

    Path path = Path.of(target);
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    Files.writeString(path, result.getResponse().getContentAsString());
  }
}
