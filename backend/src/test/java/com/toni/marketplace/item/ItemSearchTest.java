package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Free-text listing search ({@code GET /api/items?q=}): the keyword is
 * matched case-insensitively against title and description, composes with
 * the {@code categoryId} filter, and blank values behave as no filter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users and items it creates
class ItemSearchTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private CategoryRepository categoryRepo;

  private String userToken;
  private Long electronicsId;

  @BeforeEach
  void createSellerAndToken() {
    User seller = users.save(new User("seller-search", "seller-search@example.com",
        passwords.encode("seller-password")));
    userToken = jwt.createTokenPair(seller).accessToken();
    electronicsId = categoryRepo.findBySlug("electronics")
        .orElseThrow(() -> new IllegalStateException("Flyway V3 did not seed categories")).getId();
  }

  private void createItem(String title, String description, Long categoryId) throws Exception {
    String cat = categoryId == null ? "null" : categoryId.toString();
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"description\":\"" + description
                + "\",\"priceCents\":1000,\"categoryId\":" + cat + "}"))
        .andExpect(status().isOk());
  }

  @Test
  void keywordMatchesTitleCaseInsensitively() throws Exception {
    createItem("Used ThinkPad T480", "laptop", null);
    createItem("USB-C cable", "charger", null);

    mockMvc.perform(get("/api/items")
            .header("Authorization", "Bearer " + userToken)
            .param("q", "thinkpad"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].title").value("Used ThinkPad T480"));
  }

  @Test
  void keywordMatchesDescriptionToo() throws Exception {
    createItem("Free mug", "pickup only, great battery story", null);
    createItem("USB-C cable", "charger", null);

    mockMvc.perform(get("/api/items")
            .header("Authorization", "Bearer " + userToken)
            .param("q", "pickup"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].title").value("Free mug"));
  }

  @Test
  void keywordComposesWithCategoryFilter() throws Exception {
    createItem("ThinkPad laptop", "computer", electronicsId);
    createItem("ThinkPad manual", "book", null);

    mockMvc.perform(get("/api/items")
            .header("Authorization", "Bearer " + userToken)
            .param("q", "thinkpad")
            .param("categoryId", electronicsId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].title").value("ThinkPad laptop"));
  }

  @Test
  void blankKeywordBehavesAsNoFilter() throws Exception {
    createItem("ThinkPad laptop", "computer", null);
    createItem("Free mug", "mug", null);

    mockMvc.perform(get("/api/items")
            .header("Authorization", "Bearer " + userToken)
            .param("q", "   "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(2));
  }

  @Test
  void percentSignIsMatchedLiterallyNotAsWildcard() throws Exception {
    createItem("100% cotton shirt", "clothing", null);
    createItem("USB-C cable", "charger", null);

    mockMvc.perform(get("/api/items")
            .header("Authorization", "Bearer " + userToken)
            .param("q", "100%"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].title").value("100% cotton shirt"));
  }

  @Test
  void normalizeKeywordEscapesWildcards() {
    assertThat(ItemService.normalizeKeyword(null)).isNull();
    assertThat(ItemService.normalizeKeyword("   ")).isNull();
    assertThat(ItemService.normalizeKeyword("  thinkpad  ")).isEqualTo("thinkpad");
    assertThat(ItemService.normalizeKeyword("100%")).isEqualTo("100\\%");
    assertThat(ItemService.normalizeKeyword("a_b\\c")).isEqualTo("a\\_b\\\\c");
  }
}
