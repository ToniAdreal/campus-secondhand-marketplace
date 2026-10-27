package com.toni.marketplace.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /api/messages/photo} (backlog #245): one optional photo
 * per message, stored through {@code ImageStorageService} under the
 * listing-photo rules and referenced by the row's {@code photo_url}.
 *
 * <p>Uploads land in {@code target/test-uploads/message-photo} (not the
 * real {@code ./uploads} dir) and are wiped after every test. The
 * message bucket is re-enabled here (the shared test profile disables
 * it) at 3 sends/minute so the photo path's throttle parity with the
 * JSON send is proved, not just asserted in a comment — every test
 * keeps its per-user send count at or below that budget, and each test
 * uses fresh users (fresh bucket keys).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users, items and messages it creates
@TestPropertySource(properties = {
    "app.uploads.dir=target/test-uploads/message-photo",
    // Small cap so the service size gate is reachable below the 5 MB
    // servlet backstop: a 17 KB file trips the service, not the servlet.
    "app.uploads.max-size=16KB",
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.message-attempts-per-minute=3",
    "app.auth.login-lockout.enabled=false"})
class MessagePhotoTest {

  private static final Path UPLOAD_DIR = Paths.get("target/test-uploads/message-photo");

  /** Minimal valid PNG (1x1 pixel), copied from the item-photo tests. */
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

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private MessageRepository messages;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

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

  private User newUser() {
    String tag = "msgphoto-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item newItem(User seller) {
    return items.save(new Item("Desk lamp", "IKEA Tertial", 12000L, seller.getId()));
  }

  private MockMultipartFile pngPhoto() {
    return new MockMultipartFile("file", "condition.png", "image/png", PNG_BYTES);
  }

  private void postPhoto(User sender, long itemId, long receiverId, String body,
                         MockMultipartFile file) throws Exception {
    mockMvc.perform(multipart("/api/messages/photo")
            .file(file)
            .param("itemId", String.valueOf(itemId))
            .param("receiverId", String.valueOf(receiverId))
            .param("body", body)
            .header("Authorization", "Bearer " + token(sender)))
        .andExpect(status().isOk());
  }

  @Test
  void photoMessageStoresPublicUrlAndThreadRendersIt() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);

    MvcResult result = mockMvc.perform(multipart("/api/messages/photo")
            .file(pngPhoto())
            .param("itemId", String.valueOf(item.getId()))
            .param("receiverId", String.valueOf(seller.getId()))
            .param("body", "Scratch on the corner, see photo")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.body").value("Scratch on the corner, see photo"))
        .andReturn();
    String photoUrl = JsonPath.read(
        result.getResponse().getContentAsString(), "$.data.photoUrl");
    assertThat(photoUrl).startsWith("/uploads/").endsWith(".png");

    // The stored file really exists under the test upload dir.
    assertThat(UPLOAD_DIR.resolve(photoUrl.substring("/uploads/".length())))
        .isRegularFile();

    // The thread read carries the same URL to the UI.
    mockMvc.perform(get("/api/messages")
            .param("itemId", String.valueOf(item.getId()))
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].photoUrl").value(photoUrl));
  }

  @Test
  void unsupportedTypeIsRejectedWith400AndLeavesNoRow() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);

    mockMvc.perform(multipart("/api/messages/photo")
            .file(new MockMultipartFile("file", "notes.txt", "text/plain",
                "not an image".getBytes()))
            .param("itemId", String.valueOf(item.getId()))
            .param("receiverId", String.valueOf(seller.getId()))
            .param("body", "see attached")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(messages.findAll()).isEmpty();
  }

  @Test
  void magicMismatchIsRejectedWith400() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);

    mockMvc.perform(multipart("/api/messages/photo")
            .file(new MockMultipartFile("file", "fake.png", "image/png",
                "this is not a png".getBytes()))
            .param("itemId", String.valueOf(item.getId()))
            .param("receiverId", String.valueOf(seller.getId()))
            .param("body", "see attached")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(messages.findAll()).isEmpty();
  }

  @Test
  void oversizePhotoIsRejectedWith400ByTheServiceCap() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);

    mockMvc.perform(multipart("/api/messages/photo")
            .file(new MockMultipartFile("file", "big.png", "image/png",
                new byte[17 * 1024]))
            .param("itemId", String.valueOf(item.getId()))
            .param("receiverId", String.valueOf(seller.getId()))
            .param("body", "see attached")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(messages.findAll()).isEmpty();
  }

  @Test
  void sellerColdMessageWithPhotoIsStill403() throws Exception {
    User seller = newUser();
    User stranger = newUser();
    Item item = newItem(seller);

    // No thread exists between seller and stranger: the #112 guard
    // applies to the photo path exactly as to the JSON send — a photo
    // must not buy a conversation the sender could not open in text.
    mockMvc.perform(multipart("/api/messages/photo")
            .file(pngPhoto())
            .param("itemId", String.valueOf(item.getId()))
            .param("receiverId", String.valueOf(stranger.getId()))
            .param("body", "cold photo")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isForbidden());

    assertThat(messages.findAll()).isEmpty();
  }

  @Test
  void photoSendSharesTheMessageRateLimitBucket() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);

    for (int i = 0; i < 3; i++) {
      postPhoto(buyer, item.getId(), seller.getId(), "photo " + i, pngPhoto());
    }
    // The 4th send in the minute trips the same bucket the JSON send uses.
    mockMvc.perform(multipart("/api/messages/photo")
            .file(pngPhoto())
            .param("itemId", String.valueOf(item.getId()))
            .param("receiverId", String.valueOf(seller.getId()))
            .param("body", "photo 3")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void jsonSendWithoutPhotoIsUnchanged() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);

    mockMvc.perform(post("/api/messages")
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + ",\"receiverId\":"
                + seller.getId() + ",\"body\":\"Is this still available?\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.photoUrl").value(nullValue()));

    assertThat(messages.findAll()).hasSize(1);
    assertThat(messages.findAll().get(0).getPhotoUrl()).isNull();
  }

  @Test
  void deletedPhotoMessageLosesItsPhotoInTheThread() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);

    MvcResult sent = mockMvc.perform(multipart("/api/messages/photo")
            .file(pngPhoto())
            .param("itemId", String.valueOf(item.getId()))
            .param("receiverId", String.valueOf(seller.getId()))
            .param("body", "photo to retract")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andReturn();
    long messageId = ((Number) JsonPath.read(
        sent.getResponse().getContentAsString(), "$.data.id")).longValue();

    mockMvc.perform(delete("/api/messages/" + messageId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.photoUrl").value(nullValue()));

    // The tombstone row carries no photo for the other party either.
    mockMvc.perform(get("/api/messages")
            .param("itemId", String.valueOf(item.getId()))
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].body").value(Message.TOMBSTONE_BODY))
        .andExpect(jsonPath("$.data.content[0].photoUrl").value(nullValue()));
  }
}
