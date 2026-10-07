package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Photo-file lifecycle via MockMvc: deleting a listing (admin-only) removes
 * its stored photo from disk instead of orphaning it, and replacing a photo
 * deletes the old file. Uploads land in
 * {@code target/test-uploads/item-photo-cleanup} (not the real
 * {@code ./uploads} dir) and are wiped after every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users and items it creates
@TestPropertySource(properties = {
    "app.uploads.dir=target/test-uploads/item-photo-cleanup"})
class ItemPhotoCleanupTest {

  private static final Path UPLOAD_DIR =
      Paths.get("target/test-uploads/item-photo-cleanup");

  /** Minimal valid PNG (1x1 pixel). */
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
  private String adminToken;

  @BeforeEach
  void createUsersAndTokens() {
    seller = users.save(new User("seller-cleanup", "seller-cleanup@example.com",
        passwords.encode("seller-password")));
    User admin = new User("admin-cleanup", "admin-cleanup@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    users.save(admin);

    sellerToken = jwt.createTokenPair(seller).accessToken();
    adminToken = jwt.createTokenPair(admin).accessToken();
  }

  @AfterEach
  void wipeUploadDir() throws IOException {
    // Delete uploaded files but keep the directory itself: the Spring bean
    // created it once at startup and does not recreate it between tests.
    if (Files.exists(UPLOAD_DIR)) {
      try (var stream = Files.walk(UPLOAD_DIR)) {
        stream.sorted(Comparator.reverseOrder())
            .filter(p -> !p.equals(UPLOAD_DIR))
            .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
      }
    }
  }

  private Item seedItem() {
    return itemRepo.save(new Item("Used ThinkPad T480s", "good battery", 25000L,
        seller.getId()));
  }

  private static MockMultipartFile pngFile() {
    return new MockMultipartFile("file", "thinkpad.png", "image/png", PNG_BYTES);
  }

  private String uploadPhoto(Long itemId, String token) throws Exception {
    String body = mockMvc.perform(multipart("/api/items/{id}/photo", itemId)
            .file(pngFile())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.photoUrl").exists())
        .andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.data.photoUrl");
  }

  private static Path fileFor(String photoUrl) {
    return UPLOAD_DIR.resolve(photoUrl.substring("/uploads/".length()));
  }

  @Test
  void deleteListing_removesStoredPhotoFromDisk() throws Exception {
    Item item = seedItem();
    String url = uploadPhoto(item.getId(), sellerToken);
    assertThat(fileFor(url)).exists();

    mockMvc.perform(delete("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    assertThat(itemRepo.findById(item.getId())).isEmpty();
    assertThat(fileFor(url)).doesNotExist();
  }

  @Test
  void replacePhoto_deletesOldFileFromDisk() throws Exception {
    Item item = seedItem();
    String first = uploadPhoto(item.getId(), sellerToken);
    assertThat(fileFor(first)).exists();

    String second = uploadPhoto(item.getId(), sellerToken);

    // Fresh UUID per upload, so the two URLs must differ.
    assertThat(second).isNotEqualTo(first);
    assertThat(fileFor(first)).doesNotExist();
    assertThat(fileFor(second)).exists();
    assertThat(itemRepo.findById(item.getId()).orElseThrow().getPhotoUrl())
        .isEqualTo(second);
  }

  @Test
  void forbiddenDelete_keepsPhotoOnDisk() throws Exception {
    Item item = seedItem();
    String url = uploadPhoto(item.getId(), sellerToken);

    // The seller is not an admin: the delete is refused before the service
    // runs, so neither the row nor its file may be touched.
    mockMvc.perform(delete("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(itemRepo.findById(item.getId())).isPresent();
    assertThat(fileFor(url)).exists();
  }
}
