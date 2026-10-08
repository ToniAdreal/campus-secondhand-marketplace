package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN category management (backlog #70): {@code POST /api/categories}
 * creates a category with case-insensitive name uniqueness (201), and
 * {@code DELETE /api/categories/{id}} removes an unused category (204).
 * Non-admin callers get the JSON 403 envelope, anonymous callers 401.
 * Deleting a category that listings reference is rejected with 409 so a
 * listing's category is never orphaned.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users, items and categories it creates
class CategoryAdminManagementTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private CategoryRepository categoryRepo;

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
    seller = users.save(new User("cat-seller", "cat-seller@example.com",
        passwords.encode("seller-password")));
    User admin = new User("cat-admin", "cat-admin@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);

    userToken = jwt.createTokenPair(seller).accessToken();
    adminToken = jwt.createTokenPair(admin).accessToken();
  }

  private String createBody(String name) {
    return "{\"name\":\"" + name + "\"}";
  }

  private String createBody(String name, String slug) {
    return "{\"name\":\"" + name + "\",\"slug\":\"" + slug + "\"}";
  }

  @Test
  void adminCreateCategoryDerivesSlug() throws Exception {
    mockMvc.perform(post("/api/categories")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("Vintage & Retro")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.name").value("Vintage & Retro"))
        .andExpect(jsonPath("$.data.slug").value("vintage-retro"))
        .andExpect(jsonPath("$.data.id").isNumber());

    Category persisted = categoryRepo.findBySlug("vintage-retro").orElseThrow();
    assertThat(persisted.getName()).isEqualTo("Vintage & Retro");
    assertThat(persisted.getNameLower()).isEqualTo("vintage & retro");
  }

  @Test
  void adminCreateCategoryWithExplicitSlug() throws Exception {
    mockMvc.perform(post("/api/categories")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("Textbooks", "textbooks-used")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.slug").value("textbooks-used"));
  }

  @Test
  void adminCreateDuplicateNameCaseVariantIs409() throws Exception {
    // "Electronics" is seeded by Flyway V3 — "electronics" must collide.
    mockMvc.perform(post("/api/categories")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("electronics")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("category name already exists"));
  }

  @Test
  void adminCreateDuplicateSlugIs409() throws Exception {
    mockMvc.perform(post("/api/categories")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("Gadgets", "electronics")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("category slug already exists"));
  }

  @Test
  void adminCreateInvalidSlugIs400() throws Exception {
    mockMvc.perform(post("/api/categories")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("Gadgets", "Not A Slug!")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void adminCreateBlankNameIs400() throws Exception {
    mockMvc.perform(post("/api/categories")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("   ")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void adminCreateSluglessNameFallsBackToCategorySlug() throws Exception {
    mockMvc.perform(post("/api/categories")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("!!!")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.slug").value("category"));
  }

  @Test
  void nonAdminCreateIsForbidden() throws Exception {
    mockMvc.perform(post("/api/categories")
            .header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("Gadgets")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403))
        .andExpect(jsonPath("$.message").value("forbidden"));

    assertThat(categoryRepo.findBySlug("gadgets")).isEmpty();
  }

  @Test
  void anonymousCreateIsUnauthorized() throws Exception {
    mockMvc.perform(post("/api/categories")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("Gadgets")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    assertThat(categoryRepo.findBySlug("gadgets")).isEmpty();
  }

  @Test
  void adminDeleteEmptyCategoryIs204() throws Exception {
    Category temp = categoryRepo.save(new Category("Temp Category", "temp-category"));

    mockMvc.perform(delete("/api/categories/{id}", temp.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNoContent());

    assertThat(categoryRepo.findById(temp.getId())).isEmpty();
  }

  @Test
  void adminDeleteCategoryInUseIs409AndSurvives() throws Exception {
    Category electronics = categoryRepo.findBySlug("electronics").orElseThrow();
    Item item = new Item("Used bike", "good condition", 50000L, seller.getId());
    item.setCategory(electronics);
    itemRepo.save(item);

    mockMvc.perform(delete("/api/categories/{id}", electronics.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("category is used by listings"));

    assertThat(categoryRepo.findById(electronics.getId())).isPresent();
  }

  @Test
  void adminDeleteUnknownCategoryIs404() throws Exception {
    mockMvc.perform(delete("/api/categories/{id}", 999999L)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("category not found"));
  }

  @Test
  void nonAdminDeleteIsForbidden() throws Exception {
    Category temp = categoryRepo.save(new Category("Temp Category", "temp-category"));

    mockMvc.perform(delete("/api/categories/{id}", temp.getId())
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(categoryRepo.findById(temp.getId())).isPresent();
  }

  @Test
  void anonymousDeleteIsUnauthorized() throws Exception {
    Category temp = categoryRepo.save(new Category("Temp Category", "temp-category"));

    mockMvc.perform(delete("/api/categories/{id}", temp.getId()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    assertThat(categoryRepo.findById(temp.getId())).isPresent();
  }
}
