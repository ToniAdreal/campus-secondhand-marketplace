package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import net.minidev.json.JSONArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /api/items/{id}/photos} + {@code DELETE
 * /api/items/{id}/photos/{photoId}} via MockMvc: the seller (or an admin)
 * grows a listing's gallery one photo at a time, the gallery is capped at
 * 6, rows are scoped to their listing, and deletes remove the file from
 * disk (best-effort, never rolling back the row delete).
 *
 * <p>Uploads land in {@code target/test-uploads/item-gallery} (not the real
 * {@code ./uploads} dir) and are wiped after every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users and items it creates
@TestPropertySource(properties = {
    "app.uploads.dir=target/test-uploads/item-gallery"})
class ItemPhotoGalleryTest {

  private static final Path UPLOAD_DIR =
      Paths.get("target/test-uploads/item-gallery");

  /** Minimal valid PNG (1x1 pixel) — small enough for the 16 KB test cap. */
  private static final byte[] PNG_BYTES = {
      (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
      0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
      0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
      0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
      (byte) 0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
      0x54, 0x78, (byte) 0x9C, 0x62, 0x60, 0x00, 0x02, 0x00,
      0x00, 0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4,
      0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
      (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

  private static final String UUID_URL_PATTERN =
      "/uploads/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}"
          + "-[0-9a-f]{4}-[0-9a-f]{12}\\.png";

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
    seller = users.save(new User("seller-gallery", "seller-gallery@example.com",
        passwords.encode("seller-password")));
    User stranger = users.save(new User("stranger-gallery", "stranger-gallery@example.com",
        passwords.encode("stranger-password")));
    User admin = new User("admin-gallery", "admin-gallery@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    users.save(admin);

    sellerToken = jwt.createTokenPair(seller).accessToken();
    strangerToken = jwt.createTokenPair(stranger).accessToken();
    adminToken = jwt.createTokenPair(admin).accessToken();
  }

  @AfterEach
  void wipeUploadDir() throws IOException {
    if (Files.exists(UPLOAD_DIR)) {
      try (var stream = Files.walk(UPLOAD_DIR)) {
        stream.sorted(Comparator.reverseOrder())
            .filter(p -> !p.equals(UPLOAD_DIR))
            .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
      }
    }
  }

  private Item seedItem() {
    return itemRepo.save(new Item("Used film camera", "works, light seals new", 45000L,
        seller.getId()));
  }

  private static MockMultipartFile pngFile() {
    return new MockMultipartFile("file", "camera.png", "image/png", PNG_BYTES);
  }

  /** Uploads one gallery photo and returns the response body. */
  private String addPhoto(Long itemId, String token) throws Exception {
    return mockMvc.perform(multipart("/api/items/{id}/photos", itemId)
            .file(pngFile())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn().getResponse().getContentAsString();
  }

  private static String fileNameOf(String publicUrl) {
    return publicUrl.substring(publicUrl.lastIndexOf('/') + 1);
  }

  @Test
  void sellerAddsFirstPhotoAndItBecomesPrimary() throws Exception {
    Item item = seedItem();

    String body = addPhoto(item.getId(), sellerToken);

    List<Map<String, Object>> photos = JsonPath.read(body, "$.data.photos");
    assertThat(photos).hasSize(1);
    assertThat(photos.get(0).get("position")).isEqualTo(0);
    String url = (String) photos.get(0).get("url");
    assertThat(url).matches(UUID_URL_PATTERN);
    assertThat(url).doesNotContain("camera"); // never the client filename

    // The stored file exists on disk.
    assertThat(UPLOAD_DIR.resolve(fileNameOf(url))).isRegularFile();
    assertThat(photoRepo.findByItemIdOrderByPositionAsc(item.getId())).hasSize(1);
  }

  @Test
  void galleryGrowsInPositionOrder() throws Exception {
    Item item = seedItem();

    addPhoto(item.getId(), sellerToken);
    String body = addPhoto(item.getId(), sellerToken);

    JSONArray positions = JsonPath.read(body, "$.data.photos[*].position");
    assertThat(positions).containsExactly(0, 1);
    JSONArray urls = JsonPath.read(body, "$.data.photos[*].url");
    assertThat(urls).hasSize(2);
    assertThat((String) urls.get(0)).isNotEqualTo((String) urls.get(1));
  }

  @Test
  void sixthPhotoIsAcceptedAndSeventhIsRejected() throws Exception {
    Item item = seedItem();

    for (int i = 0; i < 6; i++) {
      addPhoto(item.getId(), sellerToken);
    }

    mockMvc.perform(multipart("/api/items/{id}/photos", item.getId())
            .file(pngFile())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422))
        .andExpect(jsonPath("$.message").value("photo limit reached: a listing holds at most 6 photos"));

    assertThat(photoRepo.findByItemIdOrderByPositionAsc(item.getId())).hasSize(6);
  }

  @Test
  void anonymousAddIsUnauthorized() throws Exception {
    Item item = seedItem();

    mockMvc.perform(multipart("/api/items/{id}/photos", item.getId()).file(pngFile()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    assertThat(photoRepo.findByItemIdOrderByPositionAsc(item.getId())).isEmpty();
  }

  @Test
  void nonOwnerAddIsForbidden() throws Exception {
    Item item = seedItem();

    mockMvc.perform(multipart("/api/items/{id}/photos", item.getId())
            .file(pngFile())
            .header("Authorization", "Bearer " + strangerToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(photoRepo.findByItemIdOrderByPositionAsc(item.getId())).isEmpty();
  }

  @Test
  void adminCanAddPhotoToAnotherSellersListing() throws Exception {
    Item item = seedItem();

    String body = addPhoto(item.getId(), adminToken);

    List<Map<String, Object>> photos = JsonPath.read(body, "$.data.photos");
    assertThat(photos).hasSize(1);
  }

  @Test
  void addToUnknownItemIsNotFound() throws Exception {
    mockMvc.perform(multipart("/api/items/{id}/photos", 999999L)
            .file(pngFile())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void nonImageContentTypeIsRejected() throws Exception {
    Item item = seedItem();

    mockMvc.perform(multipart("/api/items/{id}/photos", item.getId())
            .file(new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes()))
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(photoRepo.findByItemIdOrderByPositionAsc(item.getId())).isEmpty();
  }

  @Test
  void sellerDeletesPhotoAndItsFile() throws Exception {
    Item item = seedItem();
    String firstBody = addPhoto(item.getId(), sellerToken);
    addPhoto(item.getId(), sellerToken);
    Long firstId = ((Number) JsonPath.<List<Map<String, Object>>>read(firstBody, "$.data.photos")
        .get(0).get("id")).longValue();
    String firstUrl = JsonPath.read(firstBody, "$.data.photos[0].url");
    Path firstFile = UPLOAD_DIR.resolve(fileNameOf(firstUrl));
    assertThat(firstFile).isRegularFile();

    mockMvc.perform(delete("/api/items/{id}/photos/{photoId}", item.getId(), firstId)
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    // Row gone, file gone, the sibling photo survives (read back via detail).
    assertThat(photoRepo.findById(firstId)).isEmpty();
    assertThat(firstFile).doesNotExist();
    String detail = mockMvc.perform(get("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    List<Map<String, Object>> photos = JsonPath.read(detail, "$.data.photos");
    assertThat(photos).hasSize(1);
    assertThat(photos.get(0).get("url")).isNotEqualTo(firstUrl);
  }

  @Test
  void deleteResponseIsThePlainSuccessEnvelope() throws Exception {
    // The DELETE response is a plain success envelope (no DTO); the gallery
    // is read back through the listing endpoints. This pins the contract.
    Item item = seedItem();
    String body = addPhoto(item.getId(), sellerToken);
    Long photoId = ((Number) JsonPath.<List<Map<String, Object>>>read(body, "$.data.photos")
        .get(0).get("id")).longValue();

    mockMvc.perform(delete("/api/items/{id}/photos/{photoId}", item.getId(), photoId)
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.message").value("ok"));
  }

  @Test
  void deletePhotoOfAnotherListingIsNotFound() throws Exception {
    Item mine = seedItem();
    Item other = seedItem();
    String body = addPhoto(mine.getId(), sellerToken);
    Long photoId = ((Number) JsonPath.<List<Map<String, Object>>>read(body, "$.data.photos")
        .get(0).get("id")).longValue();

    // The photo exists, but not under this listing: 404, no cross-listing
    // delete, the row and the file survive.
    String url = JsonPath.read(body, "$.data.photos[0].url");
    mockMvc.perform(delete("/api/items/{id}/photos/{photoId}", other.getId(), photoId)
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));

    assertThat(photoRepo.findById(photoId)).isPresent();
    assertThat(UPLOAD_DIR.resolve(fileNameOf(url))).isRegularFile();
  }

  @Test
  void nonOwnerDeleteIsForbidden() throws Exception {
    Item item = seedItem();
    String body = addPhoto(item.getId(), sellerToken);
    Long photoId = ((Number) JsonPath.<List<Map<String, Object>>>read(body, "$.data.photos")
        .get(0).get("id")).longValue();

    mockMvc.perform(delete("/api/items/{id}/photos/{photoId}", item.getId(), photoId)
            .header("Authorization", "Bearer " + strangerToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(photoRepo.findById(photoId)).isPresent();
  }

  @Test
  void deleteListingRemovesEveryGalleryFile() throws Exception {
    Item item = seedItem();
    String body1 = addPhoto(item.getId(), sellerToken);
    String body2 = addPhoto(item.getId(), sellerToken);
    // The legacy single photo (photo_url) is cleaned up in the same pass.
    String legacyBody = mockMvc.perform(multipart("/api/items/{id}/photo", item.getId())
            .file(pngFile())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String legacyUrl = JsonPath.read(legacyBody, "$.data.photoUrl");

    List<String> urls = List.of(
        JsonPath.read(body1, "$.data.photos[0].url"),
        JsonPath.read(body2, "$.data.photos[1].url"));
    for (String url : urls) {
      assertThat(UPLOAD_DIR.resolve(fileNameOf(url))).isRegularFile();
    }
    assertThat(UPLOAD_DIR.resolve(fileNameOf(legacyUrl))).isRegularFile();

    mockMvc.perform(delete("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());

    for (String url : urls) {
      assertThat(UPLOAD_DIR.resolve(fileNameOf(url))).doesNotExist();
    }
    assertThat(UPLOAD_DIR.resolve(fileNameOf(legacyUrl))).doesNotExist();
    assertThat(photoRepo.findByItemIdOrderByPositionAsc(item.getId())).isEmpty();
  }

  @Test
  void listViewCarriesTheGallery() throws Exception {
    Item item = seedItem();
    addPhoto(item.getId(), sellerToken);
    addPhoto(item.getId(), sellerToken);

    String body = mockMvc.perform(get("/api/items")
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    List<Map<String, Object>> photos = JsonPath.read(body, "$.data.content[0].photos");
    assertThat(photos).hasSize(2);
    assertThat(photos.get(0).get("position")).isEqualTo(0);
    assertThat(photos.get(1).get("position")).isEqualTo(1);
  }

  @Test
  void detailViewCarriesTheGalleryWithPrimaryFirst() throws Exception {
    Item item = seedItem();
    addPhoto(item.getId(), sellerToken);
    String body = addPhoto(item.getId(), sellerToken);

    String detail = mockMvc.perform(get("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    List<Map<String, Object>> photos = JsonPath.read(detail, "$.data.photos");
    List<Map<String, Object>> listed = JsonPath.read(body, "$.data.photos");
    assertThat(photos).hasSize(2);
    // Same gallery as the add-photo response, primary (position 0) first.
    assertThat(photos.get(0).get("id")).isEqualTo(listed.get(0).get("id"));
    assertThat(photos.get(0).get("position")).isEqualTo(0);
  }
}
