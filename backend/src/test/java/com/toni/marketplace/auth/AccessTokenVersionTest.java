package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bearer-token kill switch on password change (#59): the user's
 * {@code tokenVersion} row is embedded in every access token's {@code tver}
 * claim and checked on each request, so a password change bumps the row and
 * every previously issued token 401s immediately — while the fresh pair
 * (minted at the new version) and other users' tokens keep working.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
class AccessTokenVersionTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void preChangeBearerToken401sAfterPasswordChange() throws Exception {
    MvcResult registered = register("alice", "alice@example.com");
    String oldAccess = accessToken(registered);

    // Sanity: the token works before the change.
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + oldAccess))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    // Change the password with the still-valid token.
    MvcResult changed = mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + oldAccess)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"s3cret-pass\",\"newPassword\":\"n3w-s3cret\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String freshAccess = accessToken(changed);

    // Pre-change token is dead on arrival…
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + oldAccess))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    // …the fresh pair from the change keeps working…
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + freshAccess))
        .andExpect(status().isOk());

    // …and a brand-new login's token works too (same version, not stale).
    MvcResult loggedIn = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"n3w-s3cret\"}"))
        .andExpect(status().isOk())
        .andReturn();
    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + accessToken(loggedIn)))
        .andExpect(status().isOk());
  }

  @Test
  void versionBumpDoesNotTouchOtherUsers() throws Exception {
    MvcResult alice = register("alice", "alice@example.com");
    MvcResult bob = register("bob", "bob@example.com");
    String aliceAccess = accessToken(alice);
    String bobAccess = accessToken(bob);

    mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + aliceAccess)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"s3cret-pass\",\"newPassword\":\"n3w-s3cret\"}"))
        .andExpect(status().isOk());

    // Alice's old token is dead…
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + aliceAccess))
        .andExpect(status().isUnauthorized());

    // …but Bob's untouched token (his row stayed at version 0) still works.
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + bobAccess))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("bob"));
  }

  private MvcResult register(String username, String email) throws Exception {
    return mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + email
                + "\",\"password\":\"s3cret-pass\"}"))
        .andExpect(status().isOk())
        .andReturn();
  }

  private String accessToken(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
  }
}
