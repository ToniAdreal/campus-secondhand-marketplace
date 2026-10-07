package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /api/auth/me end-to-end via MockMvc: the caller's own profile for SPA
 * session restore. Anonymous callers get the JSON 401 envelope; the body is
 * the UserSummary projection (id/username/email/roles) — the password hash
 * must never appear in the JSON.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
class AuthMeTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  private static final String REGISTER =
      "{\"username\":\"alice\",\"email\":\"alice@example.com\",\"password\":\"s3cret-pass\"}";

  @Test
  void anonymousMeReturns401Envelope() throws Exception {
    register();
    mockMvc.perform(get("/api/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("unauthorized"));
  }

  @Test
  void meReturnsCallerProfileWithoutPasswordHash() throws Exception {
    String access = accessToken(register());

    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.id").isNumber())
        .andExpect(jsonPath("$.data.username").value("alice"))
        .andExpect(jsonPath("$.data.email").value("alice@example.com"))
        .andExpect(jsonPath("$.data.roles.length()").value(1))
        .andExpect(jsonPath("$.data.roles[0]").value("USER"))
        // The User entity is never serialized: the hash stays server-side.
        .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
        .andExpect(jsonPath("$.data.password").doesNotExist());
  }

  @Test
  void meReflectsCurrentRolesSorted() throws Exception {
    accessToken(register());
    User alice = users.findByUsernameIgnoreCase("alice").orElseThrow();
    alice.setRoles(EnumSet.of(Role.USER, Role.ADMIN));
    users.save(alice);

    String fresh = accessToken(login("alice", "s3cret-pass"));

    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + fresh))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.roles.length()").value(2))
        .andExpect(jsonPath("$.data.roles[0]").value("ADMIN"))
        .andExpect(jsonPath("$.data.roles[1]").value("USER"));
  }

  private MvcResult register() throws Exception {
    return mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REGISTER))
        .andExpect(status().isOk())
        .andReturn();
  }

  private MvcResult login(String usernameOrEmail, String password) throws Exception {
    return mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + usernameOrEmail
                + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
  }

  private String accessToken(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
  }
}
