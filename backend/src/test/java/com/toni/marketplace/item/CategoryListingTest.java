package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Category taxonomy (Flyway V3): categories are seeded by migration, a
 * listing can be created with a category id, an unknown category id is
 * rejected 404, and the list view supports {@code ?categoryId=}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users and items it creates
class CategoryListingTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository itemRepo;

  @Autowired
  private CategoryRepository categoryRepo;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private String userToken;
  private Long electronicsId;

  @BeforeEach
  void createSellerAndToken() {
    User seller = users.save(new User("seller-cat", "seller-cat@example.com",
        passwords.encode("seller-password")));
    userToken = jwt.createTokenPair(seller).accessToken();
    electronicsId = categoryRepo.findBySlug("electronics")
        .orElseThrow(() -> new IllegalStateException("Flyway V3 did not seed categories")).getId();
  }

  private String createBody(String title, Long categoryId) {
    String cat = categoryId == null ? "null" : categoryId.toString();
    return "{\"title\":\"" + title + "\",\"description\":\"d\",\"priceCents\":1000"
        + ",\"categoryId\":" + cat + "}";
  }

  @Test
  void seededCategoriesAreAvailable() {
    assertThat(categoryRepo.findAll()).hasSizeGreaterThanOrEqualTo(6);
    assertThat(categoryRepo.findBySlug("books-study")).isPresent();
  }

  @Test
  void creatingListingWithCategoryAttachesIt() throws Exception {
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("Used ThinkPad", electronicsId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.categoryId").value(electronicsId.intValue()))
        .andExpect(jsonPath("$.data.categoryName").value("Electronics"));

    assertThat(itemRepo.findAll())
        .hasSize(1)
        .first().satisfies(item ->
            assertThat(item.getCategory().getSlug()).isEqualTo("electronics"));
  }

  @Test
  void creatingListingWithoutCategoryLeavesItUncategorized() throws Exception {
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("Free mug", null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.categoryId", nullValue()))
        .andExpect(jsonPath("$.data.categoryName", nullValue()));

    assertThat(itemRepo.findAll())
        .hasSize(1)
        .first().satisfies(item -> assertThat(item.getCategory()).isNull());
  }

  @Test
  void unknownCategoryIdIsRejectedWith404Envelope() throws Exception {
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("Used ThinkPad", 999999L)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));

    assertThat(itemRepo.findAll()).isEmpty();
  }

  @Test
  void listFiltersByCategory() throws Exception {
    createItem("Laptop", electronicsId);
    createItem("Mug", null);

    mockMvc.perform(get("/api/items")
            .header("Authorization", "Bearer " + userToken)
            .param("categoryId", electronicsId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].title").value("Laptop"));

    mockMvc.perform(get("/api/items")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(2));
  }

  private void createItem(String title, Long categoryId) throws Exception {
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(title, categoryId)))
        .andExpect(status().isOk());
  }
}
