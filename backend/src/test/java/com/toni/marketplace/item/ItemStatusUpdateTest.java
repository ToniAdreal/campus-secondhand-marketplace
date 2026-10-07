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
 * Lifecycle for {@code PATCH /api/items/{id}/status}: the seller or an ADMIN
 * may mark a listing SOLD; anyone else gets 403, anonymous callers get 401,
 * and the transition rules are enforced — only AVAILABLE/RESERVED → SOLD
 * exist, anything else is answered 422 in the JSON envelope.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users and items it creates
class ItemStatusUpdateTest {

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
  private String sellerToken;
  private String otherToken;
  private String adminToken;

  @BeforeEach
  void createUsersAndTokens() {
    seller = users.save(new User("seller-st", "seller-st@example.com",
        passwords.encode("seller-password")));
    User other = users.save(new User("other-st", "other-st@example.com",
        passwords.encode("other-password")));
    User admin = new User("alice-admin-st", "alice-admin-st@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);

    sellerToken = jwt.createTokenPair(seller).accessToken();
    otherToken = jwt.createTokenPair(other).accessToken();
    adminToken = jwt.createTokenPair(admin).accessToken();
  }

  private Item seedItem(ItemStatus status) {
    Item item = new Item("Used ThinkPad T480s", "good battery", 25000L, seller.getId());
    item.setStatus(status);
    return itemRepo.save(item);
  }

  private static String body(String json) {
    return json;
  }

  @Test
  void ownerMarksAvailableListingSold() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(patch("/api/items/{id}/status", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("{\"status\":\"SOLD\"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.status").value("SOLD"));

    assertThat(itemRepo.findById(item.getId())).get()
        .extracting(Item::getStatus).isEqualTo(ItemStatus.SOLD);
  }

  @Test
  void ownerMarksReservedListingSold() throws Exception {
    Item item = seedItem(ItemStatus.RESERVED);

    mockMvc.perform(patch("/api/items/{id}/status", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("{\"status\":\"SOLD\"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("SOLD"));

    assertThat(itemRepo.findById(item.getId())).get()
        .extracting(Item::getStatus).isEqualTo(ItemStatus.SOLD);
  }

  @Test
  void adminMarksOtherUsersListingSold() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(patch("/api/items/{id}/status", item.getId())
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("{\"status\":\"SOLD\"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("SOLD"));
  }

  @Test
  void nonOwnerIsForbiddenAndStatusUnchanged() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(patch("/api/items/{id}/status", item.getId())
            .header("Authorization", "Bearer " + otherToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("{\"status\":\"SOLD\"}")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403))
        .andExpect(jsonPath("$.message").value("forbidden"));

    assertThat(itemRepo.findById(item.getId())).get()
        .extracting(Item::getStatus).isEqualTo(ItemStatus.AVAILABLE);
  }

  @Test
  void unauthenticatedMarkSoldIsUnauthorized() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(patch("/api/items/{id}/status", item.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("{\"status\":\"SOLD\"}")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    assertThat(itemRepo.findById(item.getId())).get()
        .extracting(Item::getStatus).isEqualTo(ItemStatus.AVAILABLE);
  }

  @Test
  void nonSoldRequestedStatusIsRejected422() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(patch("/api/items/{id}/status", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("{\"status\":\"AVAILABLE\"}")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    assertThat(itemRepo.findById(item.getId())).get()
        .extracting(Item::getStatus).isEqualTo(ItemStatus.AVAILABLE);
  }

  @Test
  void alreadySoldListingCannotTransitionAgain() throws Exception {
    Item item = seedItem(ItemStatus.SOLD);

    mockMvc.perform(patch("/api/items/{id}/status", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("{\"status\":\"SOLD\"}")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    assertThat(itemRepo.findById(item.getId())).get()
        .extracting(Item::getStatus).isEqualTo(ItemStatus.SOLD);
  }

  @Test
  void missingStatusIsRejected400() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(patch("/api/items/{id}/status", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("{}")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("status: status must be set"));
  }

  @Test
  void unparseableStatusIsRejected400Not500() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(patch("/api/items/{id}/status", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("{\"status\":\"BOGUS\"}")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("malformed request body"));
  }

  @Test
  void unknownItemGets404() throws Exception {
    mockMvc.perform(patch("/api/items/{id}/status", 999999L)
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("{\"status\":\"SOLD\"}")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("item not found"));
  }
}
