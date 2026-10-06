package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.common.GlobalExceptionHandler;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * {@code POST /api/items/{id}/photo} via MockMvc multipart: only the
 * listing's seller (or an admin) may upload; content type and size are
 * validated; the stored file gets a UUID filename and is publicly served
 * under {@code /uploads/**}.
 *
 * <p>Uploads land in {@code target/test-uploads/item-photo} (not the real
 * {@code ./uploads} dir) and are wiped after every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users and items it creates
@TestPropertySource(properties = {
    "app.uploads.dir=target/test-uploads/item-photo"})
class ItemPhotoUploadTest {

  private static final Path UPLOAD_DIR =
      Paths.get("target/test-uploads/item-photo");

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
  private String strangerToken;
  private String adminToken;

  @BeforeEach
  void createUsersAndTokens() {
    seller = users.save(new User("seller-photo", "seller-photo@example.com",
        passwords.encode("seller-password")));
    User stranger = users.save(new User("stranger-photo", "stranger-photo@example.com",
        passwords.encode("stranger-password")));
    User admin = new User("admin-photo", "admin-photo@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    users.save(admin);

    sellerToken = jwt.createTokenPair(seller).accessToken();
    strangerToken = jwt.createTokenPair(stranger).accessToken();
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

  private static MockMultipartFile textFile() {
    return new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes());
  }

  @Test
  void sellerCanUploadPhotoAndItIsServedPublicly() throws Exception {
    Item item = seedItem();

    String body = mockMvc.perform(multipart("/api/items/{id}/photo", item.getId())
            .file(pngFile())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.id").value(item.getId()))
        .andExpect(jsonPath("$.data.photoUrl").exists())
        .andReturn().getResponse().getContentAsString();

    // The photoUrl must be a UUID filename with an extension derived from the
    // content type — never the client's original filename ("thinkpad.png").
    String url = JsonPath.read(body, "$.data.photoUrl");
    assertThat(url).matches("/uploads/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}"
        + "-[0-9a-f]{4}-[0-9a-f]{12}\\.png");
    assertThat(url).doesNotContain("thinkpad");

    assertThat(itemRepo.findById(item.getId()).orElseThrow().getPhotoUrl()).isEqualTo(url);

    // Served without authentication by the /uploads/** static mapping.
    mockMvc.perform(get(url))
        .andExpect(status().isOk())
        .andExpect(content().contentType("image/png"))
        .andExpect(content().bytes(PNG_BYTES));
  }

  @Test
  void anonymousUploadIsUnauthorized() throws Exception {
    Item item = seedItem();

    mockMvc.perform(multipart("/api/items/{id}/photo", item.getId()).file(pngFile()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    assertThat(itemRepo.findById(item.getId()).orElseThrow().getPhotoUrl()).isNull();
  }

  @Test
  void nonOwnerUploadIsForbidden() throws Exception {
    Item item = seedItem();

    mockMvc.perform(multipart("/api/items/{id}/photo", item.getId())
            .file(pngFile())
            .header("Authorization", "Bearer " + strangerToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(itemRepo.findById(item.getId()).orElseThrow().getPhotoUrl()).isNull();
  }

  @Test
  void adminCanUploadPhotoForAnotherSellersListing() throws Exception {
    Item item = seedItem();

    mockMvc.perform(multipart("/api/items/{id}/photo", item.getId())
            .file(pngFile())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.photoUrl").exists());
  }

  @Test
  void uploadToUnknownItemIsNotFound() throws Exception {
    mockMvc.perform(multipart("/api/items/{id}/photo", 999999L)
            .file(pngFile())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void nonImageContentTypeIsRejected() throws Exception {
    Item item = seedItem();

    mockMvc.perform(multipart("/api/items/{id}/photo", item.getId())
            .file(textFile())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("unsupported image type: text/plain"));

    assertThat(itemRepo.findById(item.getId()).orElseThrow().getPhotoUrl()).isNull();
  }

  @Test
  void emptyFileIsRejected() throws Exception {
    Item item = seedItem();

    mockMvc.perform(multipart("/api/items/{id}/photo", item.getId())
            .file(new MockMultipartFile("file", "empty.png", "image/png", new byte[0]))
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("image file is required"));
  }

  @Test
  void missingFilePartIsRejected() throws Exception {
    Item item = seedItem();

    mockMvc.perform(multipart("/api/items/{id}/photo", item.getId())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("missing required part: file"));
  }

  /**
   * Oversized multipart bodies never reach the controller in a real servlet
   * container — Spring rejects them first with
   * {@code MaxUploadSizeExceededException}. MockMvc bypasses container-level
   * multipart parsing, so this path is asserted directly against the
   * exception handler: 413 with the JSON envelope.
   */
  @Test
  void oversizedMultipartMapsTo413Envelope() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    var response =
        handler.handleTooLarge(new MaxUploadSizeExceededException(16 * 1024L));

    assertThat(response.getStatusCode().value()).isEqualTo(413);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(413);
    assertThat(response.getBody().message()).isEqualTo("image too large");
  }
}
