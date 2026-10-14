package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code PUT /api/items/{id}/photos/order} via MockMvc (backlog #162):
 * the seller (or an ADMIN) rewrites the gallery order with the
 * complete photo-id list; the first id becomes the primary photo.
 * Anything that is not an exact permutation of the current gallery —
 * a missing id, a duplicated id, or a photo id from another listing —
 * is 400 in the envelope and leaves every position untouched.
 *
 * <p>Gallery rows are seeded directly through the repository (URLs
 * are inert strings here — reorder never touches the files on disk).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users, items and photos it creates
class ItemPhotoReorderTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository itemRepo;

  @Autowired
  private ItemPhotoRepository photoRepo;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private User seller;
  private String sellerToken;
  private String strangerToken;
  private String adminToken;

  @BeforeEach
  void createUsersAndTokens() {
    seller = users.save(new User("seller-reorder", "seller-reorder@example.com",
        passwords.encode("seller-password")));
    User stranger = users.save(new User("stranger-reorder", "stranger-reorder@example.com",
        passwords.encode("stranger-password")));
    User admin = new User("admin-reorder", "admin-reorder@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    users.save(admin);

    sellerToken = jwt.createTokenPair(seller).accessToken();
    strangerToken = jwt.createTokenPair(stranger).accessToken();
    adminToken = jwt.createTokenPair(admin).accessToken();
  }

  private Item seedItem() {
    return itemRepo.save(new Item("Used film camera", "works, light seals new", 45000L,
        seller.getId()));
  }

  /** Seeds a 3-photo gallery at positions 0,1,2 and returns the photo ids in order. */
  private List<Long> seedGallery(Item item) {
    return List.of(
        photoRepo.save(new ItemPhoto(item, "/uploads/a.png", 0)).getId(),
        photoRepo.save(new ItemPhoto(item, "/uploads/b.png", 1)).getId(),
        photoRepo.save(new ItemPhoto(item, "/uploads/c.png", 2)).getId());
  }

  private static String orderBody(List<Long> photoIds) {
    StringBuilder sb = new StringBuilder("{\"photoIds\":[");
    for (int i = 0; i < photoIds.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(photoIds.get(i));
    }
    return sb.append("]}").toString();
  }

  private List<Integer> storedPositions(Long itemId) {
    return photoRepo.findByItemIdOrderByPositionAsc(itemId).stream()
        .map(ItemPhoto::getPosition)
        .toList();
  }

  private List<Long> storedIdsInOrder(Long itemId) {
    return photoRepo.findByItemIdOrderByPositionAsc(itemId).stream()
        .map(ItemPhoto::getId)
        .toList();
  }

  @Test
  void sellerReordersGalleryAndPrimaryFlips() throws Exception {
    Item item = seedItem();
    List<Long> ids = seedGallery(item);
    List<Long> newOrder = List.of(ids.get(2), ids.get(0), ids.get(1));

    String body = mockMvc.perform(put("/api/items/{id}/photos/order", item.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(newOrder))
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn().getResponse().getContentAsString();

    // The response carries the gallery in the new order, positions 0..n-1,
    // so the former last photo is now the primary (position 0).
    List<Map<String, Object>> photos = JsonPath.read(body, "$.data.photos");
    assertThat(photos).hasSize(3);
    assertThat(photos.stream().map(p -> ((Number) p.get("id")).longValue()).toList())
        .containsExactlyElementsOf(newOrder);
    assertThat(photos.stream().map(p -> p.get("position")).toList())
        .containsExactly(0, 1, 2);
    assertThat(photos.get(0).get("url")).isEqualTo("/uploads/c.png");

    // And the stored rows agree.
    assertThat(storedIdsInOrder(item.getId())).containsExactlyElementsOf(newOrder);
    assertThat(storedPositions(item.getId())).containsExactly(0, 1, 2);
  }

  @Test
  void adminCanReorderAnotherSellersGallery() throws Exception {
    Item item = seedItem();
    List<Long> ids = seedGallery(item);
    List<Long> newOrder = List.of(ids.get(1), ids.get(2), ids.get(0));

    mockMvc.perform(put("/api/items/{id}/photos/order", item.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(newOrder))
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    assertThat(storedIdsInOrder(item.getId())).containsExactlyElementsOf(newOrder);
  }

  @Test
  void incompleteListIs400AndNothingMoves() throws Exception {
    Item item = seedItem();
    List<Long> ids = seedGallery(item);

    mockMvc.perform(put("/api/items/{id}/photos/order", item.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(List.of(ids.get(1), ids.get(0))))
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message")
            .value("photoIds must list every photo in the gallery exactly once"));

    assertThat(storedIdsInOrder(item.getId())).containsExactlyElementsOf(ids);
    assertThat(storedPositions(item.getId())).containsExactly(0, 1, 2);
  }

  @Test
  void foreignPhotoIdIs400AndNothingMoves() throws Exception {
    Item item = seedItem();
    List<Long> ids = seedGallery(item);
    Item other = seedItem();
    Long foreignId = photoRepo.save(new ItemPhoto(other, "/uploads/foreign.png", 0)).getId();

    mockMvc.perform(put("/api/items/{id}/photos/order", item.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(List.of(ids.get(0), ids.get(1), foreignId)))
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(storedIdsInOrder(item.getId())).containsExactlyElementsOf(ids);
    assertThat(storedPositions(item.getId())).containsExactly(0, 1, 2);
  }

  @Test
  void duplicatedPhotoIdIs400() throws Exception {
    Item item = seedItem();
    List<Long> ids = seedGallery(item);

    mockMvc.perform(put("/api/items/{id}/photos/order", item.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(List.of(ids.get(0), ids.get(0), ids.get(1))))
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(storedIdsInOrder(item.getId())).containsExactlyElementsOf(ids);
  }

  @Test
  void missingPhotoIdsFieldIs400() throws Exception {
    Item item = seedItem();
    seedGallery(item);

    mockMvc.perform(put("/api/items/{id}/photos/order", item.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}")
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void nonOwnerReorderIsForbidden() throws Exception {
    Item item = seedItem();
    List<Long> ids = seedGallery(item);

    mockMvc.perform(put("/api/items/{id}/photos/order", item.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(List.of(ids.get(2), ids.get(1), ids.get(0))))
            .header("Authorization", "Bearer " + strangerToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(storedIdsInOrder(item.getId())).containsExactlyElementsOf(ids);
  }

  @Test
  void anonymousReorderIsUnauthorized() throws Exception {
    Item item = seedItem();
    List<Long> ids = seedGallery(item);

    mockMvc.perform(put("/api/items/{id}/photos/order", item.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(ids)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void reorderOfUnknownItemIsNotFound() throws Exception {
    mockMvc.perform(put("/api/items/{id}/photos/order", 999999L)
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(List.of(1L)))
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }
}
