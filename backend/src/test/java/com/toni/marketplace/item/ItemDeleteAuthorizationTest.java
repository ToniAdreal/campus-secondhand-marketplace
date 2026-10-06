package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.util.EnumSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * RBAC for {@code DELETE /api/items/{id}} (admin-only listing removal):
 * an admin token deletes, a normal-user token is rejected with the JSON 403
 * envelope, no token with 401, and an admin deleting a missing item gets the
 * project's 404 envelope.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users and items it creates
class ItemDeleteAuthorizationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository itemRepo;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private User seller;
  private String userToken;
  private String adminToken;

  @BeforeEach
  void createUsersAndTokens() {
    seller = users.save(new User("seller-rb", "seller-rb@example.com",
        passwords.encode("seller-password")));
    User admin = new User("alice-admin", "alice-admin@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);

    userToken = jwt.createTokenPair(seller).accessToken();
    adminToken = jwt.createTokenPair(admin).accessToken();
  }

  private Item seedItem() {
    return itemRepo.save(new Item("Used ThinkPad T480s", "good battery", 25000L,
        seller.getId()));
  }

  @Test
  void adminCanDeleteListing() throws Exception {
    Item item = seedItem();

    mockMvc.perform(delete("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.message").value("ok"));

    assertThat(itemRepo.findById(item.getId())).isEmpty();
  }

  @Test
  void normalUserIsForbiddenAndListingSurvives() throws Exception {
    Item item = seedItem();

    mockMvc.perform(delete("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403))
        .andExpect(jsonPath("$.message").value("forbidden"));

    assertThat(itemRepo.findById(item.getId())).isPresent();
  }

  @Test
  void unauthenticatedDeleteIsUnauthorized() throws Exception {
    Item item = seedItem();

    mockMvc.perform(delete("/api/items/{id}", item.getId()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    assertThat(itemRepo.findById(item.getId())).isPresent();
  }

  @Test
  void adminDeletingMissingItemGets404() throws Exception {
    mockMvc.perform(delete("/api/items/{id}", 999999L)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("item not found"));
  }
}
