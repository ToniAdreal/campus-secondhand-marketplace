package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Listing view count (backlog #186): the detail GET increments the
 * listing's {@code view_count} atomically, but only for an authenticated
 * non-seller, non-ADMIN caller — the seller's own views and ADMIN views
 * do not count, anonymous callers cannot read the detail at all (401,
 * no count), and list/browse reads never increment. The counter is
 * exposed only on the detail surface and the seller's own My-listings
 * surface: browse and favorites payloads omit the {@code viewCount}
 * key entirely (the DTO component is {@code NON_NULL}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ItemViewCountTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private User seller;
  private String sellerToken;
  private String buyerToken;
  private String adminToken;
  private Item item;

  @BeforeEach
  void seed() {
    seller = users.save(new User("view-seller", "view-seller@example.com",
        passwords.encode("seller-password")));
    sellerToken = jwt.createTokenPair(seller).accessToken();
    User buyer = users.save(new User("view-buyer", "view-buyer@example.com",
        passwords.encode("buyer-password")));
    buyerToken = jwt.createTokenPair(buyer).accessToken();
    User admin = new User("view-admin", "view-admin@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    users.save(admin);
    adminToken = jwt.createTokenPair(admin).accessToken();
    item = items.save(new Item("Viewed bike", "rides fine", 12000L, seller.getId()));
  }

  private long storedViewCount() {
    // Read through the detail projection (always SQL) rather than
    // findById: the managed entity in this test's persistence context
    // still carries the pre-increment value, because the service's
    // @Modifying UPDATE bypasses the first-level cache by design.
    return items.findDetailViewById(item.getId()).orElseThrow().viewCount();
  }

  @Test
  void buyerDetailReadsIncrementAndReturnThePostIncrementCount() throws Exception {
    mockMvc.perform(get("/api/items/" + item.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.viewCount").value(1));

    mockMvc.perform(get("/api/items/" + item.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.viewCount").value(2));

    assertThat(storedViewCount()).isEqualTo(2);
  }

  @Test
  void sellerDetailReadsDoNotCountButStillShowTheCount() throws Exception {
    mockMvc.perform(get("/api/items/" + item.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk());

    // The seller sees the accumulated count on their own detail read,
    // and the read itself adds nothing.
    mockMvc.perform(get("/api/items/" + item.getId())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.viewCount").value(1));
    mockMvc.perform(get("/api/items/" + item.getId())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.viewCount").value(1));

    assertThat(storedViewCount()).isEqualTo(1);
  }

  @Test
  void adminDetailReadsDoNotCount() throws Exception {
    mockMvc.perform(get("/api/items/" + item.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.viewCount").value(0));

    assertThat(storedViewCount()).isEqualTo(0);
  }

  @Test
  void anonymousDetailReadIs401AndDoesNotCount() throws Exception {
    mockMvc.perform(get("/api/items/" + item.getId()))
        .andExpect(status().isUnauthorized());

    assertThat(storedViewCount()).isEqualTo(0);
  }

  @Test
  void listReadsNeverIncrementAndBrowseOmitsTheViewCountKey() throws Exception {
    // Public browse as a buyer: no increment, no viewCount key.
    mockMvc.perform(get("/api/items")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].viewCount").doesNotExist());

    // The seller's My-listings surface DOES carry the key — still 0,
    // proving the browse read above counted nothing.
    mockMvc.perform(get("/api/items").param("mine", "true")
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].viewCount").value(0));

    assertThat(storedViewCount()).isEqualTo(0);
  }

  @Test
  void favoritesPayloadOmitsTheViewCountKey() throws Exception {
    mockMvc.perform(put("/api/users/me/favorites/" + item.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/users/me/favorites")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].viewCount").doesNotExist());

    assertThat(storedViewCount()).isEqualTo(0);
  }

  @Test
  void myListingsShowsTheAccumulatedCountToTheSeller() throws Exception {
    mockMvc.perform(get("/api/items/" + item.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/items/" + item.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/items").param("mine", "true")
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].viewCount").value(2));
  }

  @Test
  void unknownListingIs404AndCountsNothing() throws Exception {
    mockMvc.perform(get("/api/items/999999")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isNotFound());

    assertThat(storedViewCount()).isEqualTo(0);
  }
}
