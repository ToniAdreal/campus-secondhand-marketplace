package com.toni.marketplace.item;

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
 * Price-range filter + allowlisted sort on {@code GET /api/items}
 * (backlog #96): inclusive cent bounds compose with q/category, the sort
 * orders by the fixed price/createdAt keys, and bad input (negative
 * bound, min &gt; max, unknown sort) is a 400 in the envelope — never a
 * raw ORDER BY injection.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ItemPriceSortTest {

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
    User seller = users.save(new User("seller-price", "seller-price@example.com",
        passwords.encode("seller-password")));
    userToken = jwt.createTokenPair(seller).accessToken();
    electronicsId = categoryRepo.findBySlug("electronics")
        .orElseThrow(() -> new IllegalStateException("Flyway V3 did not seed categories")).getId();
  }

  private void createItem(String title, long priceCents, Long categoryId) throws Exception {
    String cat = categoryId == null ? "null" : categoryId.toString();
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"description\":\"listing\",\"priceCents\":"
                + priceCents + ",\"categoryId\":" + cat + "}"))
        .andExpect(status().isOk());
  }

  private void seedThree() throws Exception {
    createItem("Cheap mug", 500L, null);
    createItem("Mid lamp", 2500L, null);
    createItem("Pricey bike", 9900L, null);
  }

  @Test
  void minPriceFiltersInclusively() throws Exception {
    seedThree();
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + userToken)
            .param("minPriceCents", "2500"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andExpect(jsonPath("$.data.content[0].title").value("Pricey bike"))
        .andExpect(jsonPath("$.data.content[1].title").value("Mid lamp"));
  }

  @Test
  void maxPriceFiltersInclusively() throws Exception {
    seedThree();
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + userToken)
            .param("maxPriceCents", "2500"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2));
  }

  @Test
  void priceRangeComposesWithKeywordAndCategory() throws Exception {
    createItem("Bike light", 1500L, electronicsId);
    createItem("Bike lock", 800L, electronicsId);
    createItem("Bike book", 1200L, null);
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + userToken)
            .param("q", "bike")
            .param("categoryId", electronicsId.toString())
            .param("minPriceCents", "1000")
            .param("maxPriceCents", "2000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].title").value("Bike light"));
  }

  @Test
  void sortPriceAscOrdersCheapestFirst() throws Exception {
    seedThree();
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + userToken)
            .param("sort", "price-asc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].title").value("Cheap mug"))
        .andExpect(jsonPath("$.data.content[1].title").value("Mid lamp"))
        .andExpect(jsonPath("$.data.content[2].title").value("Pricey bike"));
  }

  @Test
  void sortPriceDescOrdersPriciestFirst() throws Exception {
    seedThree();
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + userToken)
            .param("sort", "price-desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].title").value("Pricey bike"))
        .andExpect(jsonPath("$.data.content[2].title").value("Cheap mug"));
  }

  @Test
  void defaultSortIsNewestFirst() throws Exception {
    seedThree();
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].title").value("Pricey bike"))
        .andExpect(jsonPath("$.data.content[2].title").value("Cheap mug"));
  }

  @Test
  void minAboveMaxIs400Envelope() throws Exception {
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + userToken)
            .param("minPriceCents", "5000").param("maxPriceCents", "100"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void negativeBoundIs400Envelope() throws Exception {
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + userToken)
            .param("minPriceCents", "-5"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void unknownSortIs400Envelope() throws Exception {
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + userToken)
            .param("sort", "cheapest"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }
}
