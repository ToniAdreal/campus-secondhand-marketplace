package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cache headers for uploaded images (backlog #176).
 *
 * <p>{@code /uploads/**} is served with
 * {@code Cache-Control: public, max-age=31536000, immutable}: stored
 * filenames are UUIDs whose bytes never change under a name
 * ({@code ImageStorageService}), so a one-year immutable policy cannot
 * serve stale content — a replaced photo gets a NEW url carrying the
 * same policy while the old url 404s.
 *
 * <p>The boundary is pinned in the other direction too: an
 * authenticated API JSON read gains no caching from this change. It
 * keeps the Spring Security policy
 * ({@code no-cache, no-store, max-age=0, must-revalidate}) and must
 * never carry {@code public}, {@code immutable} or the one-year
 * max-age — the pinned-absence pattern of backlog #166, applied to the
 * positive-caching directives.
 *
 * <p>Uploads land in {@code target/test-uploads/upload-cache} and are
 * wiped after every test (same pattern as {@code ItemPhotoUploadTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "app.uploads.dir=target/test-uploads/upload-cache"})
class UploadCacheHeadersTest {

  private static final Path UPLOAD_DIR =
      Paths.get("target/test-uploads/upload-cache");

  /** Minimal valid PNG (1x1 pixel), same fixture as ItemPhotoUploadTest. */
  private static final byte[] PNG_BYTES = {
      (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
      0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
      0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
      0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
      (byte) 0x89, 0x00, 0x00, 0x00, 0x0B, 0x49, 0x44, 0x41,
      0x54, 0x78, (byte) 0x9C, 0x63, 0x60, 0x00, 0x02, 0x00,
      0x00, 0x05, 0x00, 0x01, 0x7A, 0x5E, (byte) 0xAB, 0x3F,
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

  @BeforeEach
  void createSellerAndToken() {
    seller = users.save(new User("seller-cache", "seller-cache@example.com",
        passwords.encode("seller-password")));
    sellerToken = jwt.createTokenPair(seller).accessToken();
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

  private static MockMultipartFile pngFile() {
    return new MockMultipartFile("file", "thinkpad.png", "image/png", PNG_BYTES);
  }

  /** Uploads a photo via the legacy single-photo endpoint; returns its url. */
  private String uploadPhoto(long itemId) throws Exception {
    String body = mockMvc.perform(multipart("/api/items/{id}/photo", itemId)
            .file(pngFile())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.data.photoUrl");
  }

  @Test
  void uploadedPhotoIsServedWithOneYearImmutablePublicCacheControl() throws Exception {
    Item item = itemRepo.save(new Item("Used ThinkPad T480s", "good battery", 25000L,
        seller.getId()));
    String url = uploadPhoto(item.getId());

    // Anonymous, like any visitor: the bytes plus the immutable policy.
    mockMvc.perform(get(url))
        .andExpect(status().isOk())
        .andExpect(content().contentType("image/png"))
        .andExpect(content().bytes(PNG_BYTES))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, allOf(
            containsString("max-age=31536000"),
            containsString("public"),
            containsString("immutable"),
            not(containsString("no-store")))));
  }

  @Test
  void replacedPhotoNewUrlCarriesSamePolicyAndOldUrlIsGone() throws Exception {
    Item item = itemRepo.save(new Item("Used ThinkPad T480s", "good battery", 25000L,
        seller.getId()));
    String firstUrl = uploadPhoto(item.getId());
    String secondUrl = uploadPhoto(item.getId());

    // Replace mints a fresh UUID name — the old name is never reused.
    assertThat(secondUrl).isNotEqualTo(firstUrl);

    mockMvc.perform(get(secondUrl))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, allOf(
            containsString("max-age=31536000"),
            containsString("public"),
            containsString("immutable"))));

    // The replaced file was deleted (#40), so a browser holding the old
    // url gets a 404, never stale bytes served under a recycled name.
    mockMvc.perform(get(firstUrl))
        .andExpect(status().isNotFound());
  }

  @Test
  void apiJsonReadGainsNoPositiveCaching() throws Exception {
    Item item = itemRepo.save(new Item("Used ThinkPad T480s", "good battery", 25000L,
        seller.getId()));

    // Representative authenticated read: per-user JSON keeps the Spring
    // Security no-store policy — no public, no immutable, no 1-year age.
    mockMvc.perform(get("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, allOf(
            containsString("no-cache"),
            containsString("no-store"),
            containsString("max-age=0"),
            not(containsString("immutable")),
            not(containsString("public")),
            not(containsString("31536000")))));
  }
}
