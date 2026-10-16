package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.order.Order;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderStatus;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public seller profiles (backlog #174): GET /api/users/{id}/profile
 * carries exactly the public fields (username, member-since,
 * live-listing count, completed-sales count — the email and every
 * other User field are excluded by construction and the key set is
 * pinned here; backlog #175 folded the review aggregate — count +
 * computed average — into the same DTO, growing the pinned set to
 * six), GET /api/users/{id}/listings is the AVAILABLE-only storefront,
 * and an unknown or disabled seller is 404 on both reads. Anonymous
 * callers are 401 (the sweep pins the same routes).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SellerProfileTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private ItemRepository itemRepo;
  @Autowired private OrderRepository orderRepo;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private ObjectMapper objectMapper;

  private User seller;
  private String sellerEmail;
  private String buyerToken;

  @BeforeEach
  void createUsers() {
    String tag = "sp-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    sellerEmail = tag + "-seller@example.com";
    seller = users.save(new User(tag + "-seller", sellerEmail,
        passwords.encode("seller-password")));
    User buyer = users.save(new User(tag + "-buyer", tag + "-buyer@example.com",
        passwords.encode("buyer-password")));
    buyerToken = jwt.createTokenPair(buyer).accessToken();

    // Storefront universe: 2 live, plus one row in each non-live state.
    seedItem("Live bike", ItemStatus.AVAILABLE);
    seedItem("Live helmet", ItemStatus.AVAILABLE);
    seedItem("Reserved lamp", ItemStatus.RESERVED);
    Item soldOne = seedItem("Sold desk", ItemStatus.SOLD);
    Item soldTwo = seedItem("Sold chair", ItemStatus.SOLD);
    seedItem("Withdrawn rug", ItemStatus.WITHDRAWN);
    // Sales: exactly 2 COMPLETED; a PAID and a PENDING order do not count.
    seedOrder(soldOne, buyer.getId(), OrderStatus.COMPLETED);
    seedOrder(soldTwo, buyer.getId(), OrderStatus.COMPLETED);
    seedOrder(soldOne, buyer.getId(), OrderStatus.PAID);
  }

  private Item seedItem(String title, ItemStatus status) {
    Item item = new Item(title, "seeded", 5000L, seller.getId());
    item.setStatus(status);
    return itemRepo.save(item);
  }

  private void seedOrder(Item item, Long buyerId, OrderStatus status) {
    Order order = new Order(item, buyerId, 5000L);
    order.setStatus(status);
    orderRepo.save(order);
  }

  @Test
  void profileCarriesExactlyThePublicFieldsWithLiveCounts() throws Exception {
    MvcResult result = mockMvc.perform(get("/api/users/{id}/profile", seller.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.username").value(seller.getUsername()))
        .andExpect(jsonPath("$.data.availableListingCount").value(2))
        .andExpect(jsonPath("$.data.completedSalesCount").value(2))
        // No reviews seeded in this suite: the #175 aggregate reads 0 / null.
        .andExpect(jsonPath("$.data.reviewCount").value(0))
        .andExpect(jsonPath("$.data.averageRating")
            .value(org.hamcrest.Matchers.nullValue()))
        .andReturn();

    JsonNode data = objectMapper
        .readTree(result.getResponse().getContentAsString()).path("data");
    Set<String> keys = new TreeSet<>();
    data.fieldNames().forEachRemaining(keys::add);
    assertThat(keys).isEqualTo(Set.of(
        "username", "memberSince", "availableListingCount", "completedSalesCount",
        "reviewCount", "averageRating"));
    assertThat(data.path("memberSince").asText())
        .isEqualTo(objectMapper.writeValueAsString(seller.getCreatedAt()).replace("\"", ""));
    // The seller's email must not appear anywhere in the payload.
    assertThat(result.getResponse().getContentAsString()).doesNotContain(sellerEmail);
  }

  @Test
  void listingsReturnOnlyTheSellersAvailableRows() throws Exception {
    MvcResult result = mockMvc.perform(get("/api/users/{id}/listings", seller.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andReturn();

    JsonNode content = objectMapper
        .readTree(result.getResponse().getContentAsString()).path("data").path("content");
    Set<String> titles = new TreeSet<>();
    for (JsonNode row : content) {
      titles.add(row.path("title").asText());
      assertThat(row.path("status").asText()).isEqualTo("AVAILABLE");
      assertThat(row.path("sellerUsername").asText()).isEqualTo(seller.getUsername());
    }
    assertThat(titles).isEqualTo(Set.of("Live bike", "Live helmet"));
  }

  @Test
  void unknownSellerIs404OnBothReads() throws Exception {
    long unknownId = 999_999_999L;
    mockMvc.perform(get("/api/users/{id}/profile", unknownId)
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    mockMvc.perform(get("/api/users/{id}/listings", unknownId)
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void disabledSellerReadsAs404OnBothReads() throws Exception {
    seller.setDisabled(true);
    users.save(seller);

    mockMvc.perform(get("/api/users/{id}/profile", seller.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/users/{id}/listings", seller.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void anonymousCallersGet401OnBothReads() throws Exception {
    mockMvc.perform(get("/api/users/{id}/profile", seller.getId()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mockMvc.perform(get("/api/users/{id}/listings", seller.getId()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
