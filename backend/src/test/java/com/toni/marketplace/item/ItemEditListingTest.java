package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * Seller edit for {@code PATCH /api/items/{id}}: the seller or an ADMIN may
 * edit title / description / price / category; anyone else gets 403,
 * anonymous callers get 401, and unknown listings are 404. Status is
 * deliberately untouched (the status machine still lives on
 * {@code PATCH /api/items/{id}/status}). Price changes are rejected 422 on
 * RESERVED and SOLD listings — the order snapshotted {@code amountCents} at
 * creation — but allowed on AVAILABLE ones. Omitted fields are left alone
 * (partial update, no category-clear path).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users, items and categories it creates
class ItemEditListingTest {

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

  private User seller;
  private String sellerToken;
  private String otherToken;
  private String adminToken;
  private Category electronics;

  @BeforeEach
  void createUsersTokensAndCategory() {
    seller = users.save(new User("seller-ed", "seller-ed@example.com",
        passwords.encode("seller-password")));
    User other = users.save(new User("other-ed", "other-ed@example.com",
        passwords.encode("other-password")));
    User admin = new User("alice-admin-ed", "alice-admin-ed@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);

    sellerToken = jwt.createTokenPair(seller).accessToken();
    otherToken = jwt.createTokenPair(other).accessToken();
    adminToken = jwt.createTokenPair(admin).accessToken();

    // The seeded taxonomy (Flyway V3) already contains Electronics —
    // reuse it instead of inserting a duplicate.
    electronics = categoryRepo.findBySlug("electronics").orElseThrow();
  }

  private Item seedItem(ItemStatus status) {
    Item item = new Item("Used ThinkPad T480s", "good battery", 25000L, seller.getId());
    item.setStatus(status);
    return itemRepo.save(item);
  }

  @Test
  void ownerEditsAllFieldsOnAvailableListing() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"ThinkPad T480s — upgraded\",\"description\":\"new battery installed\","
                + "\"priceCents\":29000,\"categoryId\":" + electronics.getId() + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.title").value("ThinkPad T480s — upgraded"))
        .andExpect(jsonPath("$.data.description").value("new battery installed"))
        .andExpect(jsonPath("$.data.priceCents").value(29000))
        .andExpect(jsonPath("$.data.categoryId").value(electronics.getId()))
        .andExpect(jsonPath("$.data.status").value("AVAILABLE"));

    Item reloaded = itemRepo.findById(item.getId()).orElseThrow();
    assertThat(reloaded.getTitle()).isEqualTo("ThinkPad T480s — upgraded");
    assertThat(reloaded.getDescription()).isEqualTo("new battery installed");
    assertThat(reloaded.getPriceCents()).isEqualTo(29000L);
    assertThat(reloaded.getCategory().getId()).isEqualTo(electronics.getId());
    assertThat(reloaded.getStatus()).isEqualTo(ItemStatus.AVAILABLE);
  }

  @Test
  void partialEditLeavesOtherFieldsUntouched() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Still the same ThinkPad, new title\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.title").value("Still the same ThinkPad, new title"))
        .andExpect(jsonPath("$.data.description").value("good battery"))
        .andExpect(jsonPath("$.data.priceCents").value(25000));

    Item reloaded = itemRepo.findById(item.getId()).orElseThrow();
    assertThat(reloaded.getDescription()).isEqualTo("good battery");
    assertThat(reloaded.getPriceCents()).isEqualTo(25000L);
    assertThat(reloaded.getCategory()).isNull();
  }

  @Test
  void priceChangeOnReservedListingIsRejected422() throws Exception {
    Item item = seedItem(ItemStatus.RESERVED);

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":99999}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    assertThat(itemRepo.findById(item.getId())).get()
        .extracting(Item::getPriceCents).isEqualTo(25000L);
  }

  @Test
  void nonPriceEditOnReservedListingStillAllowed() throws Exception {
    Item item = seedItem(ItemStatus.RESERVED);

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"description\":\"buyer asked about the charger\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.description").value("buyer asked about the charger"));

    assertThat(itemRepo.findById(item.getId())).get()
        .extracting(Item::getPriceCents).isEqualTo(25000L);
  }

  @Test
  void priceChangeOnSoldListingIsRejected422() throws Exception {
    Item item = seedItem(ItemStatus.SOLD);

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":99999}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));
  }

  @Test
  void adminEditsOtherUsersListing() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Fixed by admin\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.title").value("Fixed by admin"));
  }

  @Test
  void nonOwnerIsForbiddenAndListingUnchanged() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + otherToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Hijacked title\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(itemRepo.findById(item.getId())).get()
        .extracting(Item::getTitle).isEqualTo("Used ThinkPad T480s");
  }

  @Test
  void unauthenticatedEditIsUnauthorized() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"anonymous edit\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void unknownItemGets404() throws Exception {
    mockMvc.perform(patch("/api/items/{id}", 999999L)
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"ghost\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void blankTitleIsRejected400() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(itemRepo.findById(item.getId())).get()
        .extracting(Item::getTitle).isEqualTo("Used ThinkPad T480s");
  }

  @Test
  void negativePriceIsRejected400() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":-100}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("priceCents: priceCents must be positive"));
  }

  @Test
  void unknownCategoryIsRejected404() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"categoryId\":999999}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));

    assertThat(itemRepo.findById(item.getId())).get()
        .extracting(Item::getCategory).isNull();
  }

  @Test
  void samePriceOnReservedListingIsNoopAndAllowed() throws Exception {
    // Re-submitting the current price is not a price *change*: the 422 lock
    // must only fire on an actual move, so idempotent PATCH clients are safe.
    Item item = seedItem(ItemStatus.RESERVED);

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"New title\",\"priceCents\":25000}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.title").value("New title"))
        .andExpect(jsonPath("$.data.priceCents").value(25000));
  }
}
