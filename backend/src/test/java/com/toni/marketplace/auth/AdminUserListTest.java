package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN user list/search (backlog #105): GET /api/admin/users — paginated,
 * newest first, optional q= substring search over the canonical-lowercase
 * username/email (case-insensitive, matching the #47 normalization).
 * Rows are the extended AdminUserDto projection (id/username/email/roles/
 * disabled/createdAt) — never the User entity, never a password hash.
 * ADMIN 200, normal user 403, anonymous 401 envelope.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminUserListTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private User admin;
  private User alice;
  private User alicia;
  private User bob;
  private String adminToken;
  private String userToken;

  @BeforeEach
  void createUsersAndTokens() {
    admin = new User("lst-admin", "lst-admin@example.com", passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);
    alice = users.save(new User("lst-alice", "lst-alice@example.com",
        passwords.encode("alice-password")));
    alicia = users.save(new User("lst-alicia", "alicia@school.example.com",
        passwords.encode("alicia-password")));
    bob = users.save(new User("lst-bob", "lst-bob@example.com",
        passwords.encode("bob-password")));
    bob.setDisabled(true);
    bob = users.save(bob);

    adminToken = jwt.createTokenPair(admin).accessToken();
    userToken = jwt.createTokenPair(alice).accessToken();
  }

  @Test
  void adminListReturnsAllUsersNewestFirstWithExtendedProjection() throws Exception {
    mockMvc.perform(get("/api/admin/users")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.totalElements").value(4))
        .andExpect(jsonPath("$.data.content[0].id").value(bob.getId()))
        .andExpect(jsonPath("$.data.content[0].username").value("lst-bob"))
        .andExpect(jsonPath("$.data.content[0].email").value("lst-bob@example.com"))
        .andExpect(jsonPath("$.data.content[0].roles[0]").value("USER"))
        .andExpect(jsonPath("$.data.content[0].disabled").value(true))
        .andExpect(jsonPath("$.data.content[0].createdAt").exists())
        .andExpect(jsonPath("$.data.content[0].passwordHash").doesNotExist())
        .andExpect(jsonPath("$.data.content[3].id").value(admin.getId()))
        .andExpect(jsonPath("$.data.content[3].roles[0]").value("ADMIN"));
  }

  @Test
  void searchMatchesUsernameSubstringCaseInsensitively() throws Exception {
    mockMvc.perform(get("/api/admin/users").param("q", "ALI")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andExpect(jsonPath("$.data.content[0].username").value("lst-alicia"))
        .andExpect(jsonPath("$.data.content[1].username").value("lst-alice"));
  }

  @Test
  void searchMatchesEmailSubstring() throws Exception {
    mockMvc.perform(get("/api/admin/users").param("q", "school.example")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].username").value("lst-alicia"));
  }

  @Test
  void searchWithNoMatchReturnsEmptyPage() throws Exception {
    mockMvc.perform(get("/api/admin/users").param("q", "no-such-user")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0))
        .andExpect(jsonPath("$.data.content").isEmpty());
  }

  @Test
  void searchTreatsLikeWildcardsLiterally() throws Exception {
    mockMvc.perform(get("/api/admin/users").param("q", "%")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));
  }

  @Test
  void paginationEnvelopeMirrorsOrderReads() throws Exception {
    mockMvc.perform(get("/api/admin/users").param("page", "1").param("size", "2")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(4))
        .andExpect(jsonPath("$.data.totalPages").value(2))
        .andExpect(jsonPath("$.data.number").value(1))
        .andExpect(jsonPath("$.data.size").value(2))
        .andExpect(jsonPath("$.data.content.length()").value(2))
        .andExpect(jsonPath("$.data.content[0].id").value(alice.getId()));
  }

  @Test
  void nonAdminListIsForbidden() throws Exception {
    mockMvc.perform(get("/api/admin/users")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  @Test
  void anonymousListIsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/admin/users"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
