package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cursor (keyset) pagination for the browse list (backlog #334):
 * {@code GET /api/items} with {@code ?cursor=} / {@code ?limit=} answers
 * {@code {items, nextCursor}} in the pinned newest-first order instead of
 * a Spring page. The key property under test is stability: a listing
 * created mid-browse must cause neither overlap nor gap across pages,
 * which offset paging cannot promise. The offset contract is untouched
 * — no test here re-pins it, and the controller routes to it whenever
 * neither cursor nor limit is present.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ItemCursorPaginationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository itemRepo;

  @Autowired
  private CategoryRepository categories;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private ObjectMapper objectMapper;

  private User seller;
  private String buyerToken;
  private String sellerToken;
  private Long electronicsId;

  @BeforeEach
  void createUsersAndTokens() {
    seller = users.save(new User("cursor-seller", "cursor-seller@example.com",
        passwords.encode("seller-password")));
    User buyer = users.save(new User("cursor-buyer", "cursor-buyer@example.com",
        passwords.encode("buyer-password")));
    buyerToken = jwt.createTokenPair(buyer).accessToken();
    sellerToken = jwt.createTokenPair(seller).accessToken();
    electronicsId = categories.findBySlug("electronics")
        .orElseThrow(() -> new IllegalStateException("Flyway V3 did not seed categories"))
        .getId();
  }

  /** Seeds {@code count} AVAILABLE listings in creation order; returns their ids oldest first. */
  private List<Long> seedItems(int count) {
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Item item = new Item("cursor-item-" + i, "seed " + i, 1000L + i, seller.getId());
      item.setCategory(categories.findById(electronicsId).orElseThrow());
      ids.add(itemRepo.save(item).getId());
    }
    return ids;
  }

  private MvcResult cursorPage(String token, String query) throws Exception {
    return mockMvc.perform(get("/api/items" + query)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
  }

  private JsonNode dataOf(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
  }

  private List<Long> idsOf(JsonNode data) {
    List<Long> ids = new ArrayList<>();
    data.get("items").forEach(node -> ids.add(node.get("id").asLong()));
    return ids;
  }

  private String nextCursorOf(JsonNode data) {
    JsonNode cursor = data.get("nextCursor");
    return cursor == null || cursor.isNull() ? null : cursor.asText();
  }

  @Test
  void firstPageServesLimitRowsNewestFirstWithNextCursor() throws Exception {
    List<Long> ids = seedItems(5);

    JsonNode data = dataOf(cursorPage(buyerToken, "?limit=2"));

    assertThat(idsOf(data)).containsExactly(ids.get(4), ids.get(3));
    assertThat(nextCursorOf(data)).isNotBlank();
  }

  @Test
  void walkingTheCursorReachesEveryRowExactlyOnceAndEndsWithNullCursor() throws Exception {
    List<Long> ids = seedItems(5);
    List<Long> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      String query = cursor == null ? "?limit=2" : "?limit=2&cursor=" + cursor;
      JsonNode data = dataOf(cursorPage(buyerToken, query));
      seen.addAll(idsOf(data));
      cursor = nextCursorOf(data);
      pages++;
      assertThat(pages).isLessThan(10); // a runaway cursor must fail the test, not hang it
    } while (cursor != null);

    assertThat(seen).containsExactlyInAnyOrderElementsOf(ids);
    assertThat(new HashSet<>(seen)).hasSize(5);
    assertThat(pages).isEqualTo(3);
  }

  @Test
  void interleavedInsertCausesNeitherOverlapNorGap() throws Exception {
    List<Long> original = seedItems(3);
    JsonNode first = dataOf(cursorPage(buyerToken, "?limit=2"));
    List<Long> firstIds = idsOf(first);
    String cursor = nextCursorOf(first);
    assertThat(firstIds).containsExactly(original.get(2), original.get(1));
    assertThat(cursor).isNotBlank();

    // A listing created after page 1: newest of all, so it must appear
    // in NEITHER page — page 2 continues the snapshot page 1 opened.
    Long interleaved = itemRepo.save(
        new Item("cursor-item-new", "arrived mid-browse", 9999L, seller.getId())).getId();

    JsonNode second = dataOf(cursorPage(buyerToken, "?limit=2&cursor=" + cursor));
    List<Long> secondIds = idsOf(second);
    assertThat(secondIds).containsExactly(original.get(0));
    assertThat(nextCursorOf(second)).isNull();

    Set<Long> union = new HashSet<>(firstIds);
    union.addAll(secondIds);
    assertThat(union).containsExactlyInAnyOrderElementsOf(original);
    assertThat(union).doesNotContain(interleaved);
  }

  @Test
  void cursorAloneFallsBackToTheDefaultLimit() throws Exception {
    seedItems(3);
    JsonNode first = dataOf(cursorPage(buyerToken, "?limit=2"));
    String cursor = nextCursorOf(first);

    JsonNode second = dataOf(cursorPage(buyerToken, "?cursor=" + cursor));
    assertThat(idsOf(second)).hasSize(1);
    assertThat(nextCursorOf(second)).isNull();
  }

  @Test
  void invalidCursorAnswers400InTheEnvelope() throws Exception {
    seedItems(1);
    mockMvc.perform(get("/api/items?cursor=not-a-cursor")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    // Well-formed base64url that decodes to nothing parseable as a tuple.
    mockMvc.perform(get("/api/items?cursor=aGVsbG8")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void limitBoundsAreEnforced() throws Exception {
    seedItems(1);
    mockMvc.perform(get("/api/items?limit=0")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/items?limit=101")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isBadRequest());
    JsonNode data = dataOf(cursorPage(buyerToken, "?limit=100"));
    assertThat(idsOf(data)).hasSize(1);
    assertThat(nextCursorOf(data)).isNull();
  }

  @Test
  void priceSortWithCursorAnswers400() throws Exception {
    seedItems(2);
    mockMvc.perform(get("/api/items?sort=price-asc&limit=2")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/items?sort=price-desc&cursor=AAAA")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isBadRequest());
  }

  @Test
  void categoryFilterComposesWithCursorPaging() throws Exception {
    List<Long> ids = seedItems(4);
    // One listing outside the category must never ride a cursor page.
    Item outsider = new Item("cursor-outsider", "uncategorized", 500L, seller.getId());
    itemRepo.save(outsider);

    List<Long> seen = new ArrayList<>();
    String cursor = null;
    do {
      String query = cursor == null
          ? "?limit=2&categoryId=" + electronicsId
          : "?limit=2&categoryId=" + electronicsId + "&cursor=" + cursor;
      JsonNode data = dataOf(cursorPage(buyerToken, query));
      seen.addAll(idsOf(data));
      cursor = nextCursorOf(data);
    } while (cursor != null);

    assertThat(seen).containsExactlyInAnyOrderElementsOf(ids);
    assertThat(seen).doesNotContain(outsider.getId());
  }

  @Test
  void mineCursorPagesTheSellerViewIncludingDrafts() throws Exception {
    List<Long> ids = seedItems(2);
    Item draft = new Item("cursor-draft", "seller draft", 700L, seller.getId());
    draft.setStatus(ItemStatus.DRAFT);
    ids.add(itemRepo.save(draft).getId());

    JsonNode first = dataOf(cursorPage(sellerToken, "?mine=true&limit=2"));
    assertThat(idsOf(first)).hasSize(2);
    assertThat(nextCursorOf(first)).isNotBlank();
    JsonNode second = dataOf(cursorPage(sellerToken,
        "?mine=true&limit=2&cursor=" + nextCursorOf(first)));
    assertThat(idsOf(second)).hasSize(1);
    assertThat(nextCursorOf(second)).isNull();

    Set<Long> union = new HashSet<>(idsOf(first));
    union.addAll(idsOf(second));
    assertThat(union).containsExactlyInAnyOrderElementsOf(ids);
  }
}
