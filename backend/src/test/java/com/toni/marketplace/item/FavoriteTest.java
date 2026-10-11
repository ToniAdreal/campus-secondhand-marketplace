package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved listings (backlog #143): PUT/DELETE /api/users/me/favorites/{itemId}
 * are idempotent, an unknown listing is 404 on both writes, anonymous
 * callers get 401, and the list is strictly the caller's own — saved
 * RESERVED/SOLD listings stay listed with their status.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FavoriteTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private ItemRepository itemRepo;
  @Autowired private FavoriteRepository favorites;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  private User seller;
  private String buyerToken;
  private String otherToken;
  private Long buyerId;

  @BeforeEach
  void createUsersAndTokens() {
    String tag = "fav-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    seller = users.save(new User(tag + "-seller", tag + "-seller@example.com",
        passwords.encode("seller-password")));
    User buyer = users.save(new User(tag + "-buyer", tag + "-buyer@example.com",
        passwords.encode("buyer-password")));
    User other = users.save(new User(tag + "-other", tag + "-other@example.com",
        passwords.encode("other-password")));
    buyerId = buyer.getId();
    buyerToken = jwt.createTokenPair(buyer).accessToken();
    otherToken = jwt.createTokenPair(other).accessToken();
  }

  private Item seedItem(ItemStatus status) {
    Item item = new Item("Favorite seed " + UUID.randomUUID().toString().substring(0, 8),
        "seeded", 4200L, seller.getId());
    item.setStatus(status);
    return itemRepo.save(item);
  }

  @Test
  void saveThenListShowsTheListingWithItsStatus() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(put("/api/users/me/favorites/{itemId}", item.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    mockMvc.perform(get("/api/users/me/favorites")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].id").value(item.getId()))
        .andExpect(jsonPath("$.data[0].status").value("AVAILABLE"))
        .andExpect(jsonPath("$.data[0].sellerUsername").value(seller.getUsername()));
  }

  @Test
  void reSaveIsIdempotentNoSecondRow() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(put("/api/users/me/favorites/{itemId}", item.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk());
    mockMvc.perform(put("/api/users/me/favorites/{itemId}", item.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk());

    assertThat(favorites.findFavoriteListView(buyerId)).hasSize(1);
  }

  @Test
  void unsaveRemovesAndUnsaveOfUnsavedIsNoOp() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);
    Item neverSaved = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(put("/api/users/me/favorites/{itemId}", item.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk());
    mockMvc.perform(delete("/api/users/me/favorites/{itemId}", item.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk());
    // Second unsave of the same listing, and unsave of a never-saved one.
    mockMvc.perform(delete("/api/users/me/favorites/{itemId}", item.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk());
    mockMvc.perform(delete("/api/users/me/favorites/{itemId}", neverSaved.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/users/me/favorites")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void unknownItemIs404OnSaveAndUnsave() throws Exception {
    mockMvc.perform(put("/api/users/me/favorites/{itemId}", 999_999L)
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    mockMvc.perform(delete("/api/users/me/favorites/{itemId}", 999_999L)
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void anonymousCallersGet401() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);
    mockMvc.perform(put("/api/users/me/favorites/{itemId}", item.getId()))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/api/users/me/favorites/{itemId}", item.getId()))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/users/me/favorites"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void listsAreIsolatedBetweenUsers() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(put("/api/users/me/favorites/{itemId}", item.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk());

    // The other user's list never leaks the buyer's save…
    mockMvc.perform(get("/api/users/me/favorites")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));

    // …and the other user's unsave cannot remove the buyer's save either.
    mockMvc.perform(delete("/api/users/me/favorites/{itemId}", item.getId())
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/users/me/favorites")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1));
  }

  @Test
  void soldListingStaysListedWithItsStatus() throws Exception {
    Item item = seedItem(ItemStatus.SOLD);

    mockMvc.perform(put("/api/users/me/favorites/{itemId}", item.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/users/me/favorites")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].status").value("SOLD"));
  }
}
