package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * ETag / conditional GET for the listing detail (backlog #166): {@code GET
 * /api/items/{id}} serves a strong ETag derived from (id, version,
 * updatedAt, DTO); a matching {@code If-None-Match} answers 304 with an
 * empty body. Every write path bumps the version / changes the DTO, so
 * the suite pins invalidation per write path — edit, SOLD flip,
 * order-driven RESERVED flip, and gallery photo add — instead of merely
 * asserting it. The list endpoint stays validator-free (pinned absence).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ItemConditionalGetTest {

  /** Minimal valid PNG (1x1 pixel), same fixture as ItemPhotoGalleryTest. */
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
  private String buyerToken;

  @BeforeEach
  void createUsersAndTokens() {
    seller = users.save(new User("etag-seller", "etag-seller@example.com",
        passwords.encode("seller-password")));
    User buyer = users.save(new User("etag-buyer", "etag-buyer@example.com",
        passwords.encode("buyer-password")));
    sellerToken = jwt.createTokenPair(seller).accessToken();
    buyerToken = jwt.createTokenPair(buyer).accessToken();
  }

  private Item seedItem() {
    return itemRepo.save(new Item("Used ThinkPad T480s", "good battery", 25000L, seller.getId()));
  }

  private MvcResult getDetail(Long id, String ifNoneMatch) throws Exception {
    var request = get("/api/items/{id}", id).header("Authorization", "Bearer " + buyerToken);
    if (ifNoneMatch != null) {
      request = request.header(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
    }
    return mockMvc.perform(request).andReturn();
  }

  private String etagOf(MvcResult result) {
    return result.getResponse().getHeader(HttpHeaders.ETAG);
  }

  @Test
  void firstGetServesEtagAndMatchingValidatorAnswers304() throws Exception {
    Item item = seedItem();

    MvcResult first = getDetail(item.getId(), null);
    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    String etag = etagOf(first);
    assertThat(etag).isNotBlank();

    mockMvc.perform(get("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + buyerToken)
            .header(HttpHeaders.IF_NONE_MATCH, etag))
        .andExpect(status().isNotModified())
        .andExpect(header().string(HttpHeaders.ETAG, etag))
        .andExpect(content().string(""));
  }

  @Test
  void weakStarAndListFormsMatchAndStaleValidatorDoesNot() throws Exception {
    Item item = seedItem();
    String etag = etagOf(getDetail(item.getId(), null));
    String unquoted = etag.replace("\"", "");

    for (String form : new String[] {"W/" + etag, "*", "\"other\", " + etag}) {
      mockMvc.perform(get("/api/items/{id}", item.getId())
              .header("Authorization", "Bearer " + buyerToken)
              .header(HttpHeaders.IF_NONE_MATCH, form))
          .andExpect(status().isNotModified());
    }
    assertThat(unquoted).isNotBlank();

    mockMvc.perform(get("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + buyerToken)
            .header(HttpHeaders.IF_NONE_MATCH, "\"deadbeef\""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(item.getId()));
  }

  @Test
  void editInvalidatesTheValidator() throws Exception {
    Item item = seedItem();
    String etag = etagOf(getDetail(item.getId(), null));

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"ThinkPad T480s — upgraded\"}"))
        .andExpect(status().isOk());

    MvcResult after = getDetail(item.getId(), etag);
    assertThat(after.getResponse().getStatus()).isEqualTo(200);
    assertThat(etagOf(after)).isNotEqualTo(etag);
  }

  @Test
  void soldFlipInvalidatesTheValidator() throws Exception {
    Item item = seedItem();
    String etag = etagOf(getDetail(item.getId(), null));

    mockMvc.perform(patch("/api/items/{id}/status", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SOLD\"}"))
        .andExpect(status().isOk());

    MvcResult after = getDetail(item.getId(), etag);
    assertThat(after.getResponse().getStatus()).isEqualTo(200);
    assertThat(etagOf(after)).isNotEqualTo(etag);
  }

  @Test
  void orderCreationReservedFlipInvalidatesTheValidator() throws Exception {
    Item item = seedItem();
    String etag = etagOf(getDetail(item.getId(), null));

    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + buyerToken)
            .header("Idempotency-Key", "key-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk());

    MvcResult after = getDetail(item.getId(), etag);
    assertThat(after.getResponse().getStatus()).isEqualTo(200);
    assertThat(etagOf(after)).isNotEqualTo(etag);
  }

  @Test
  void photoAddInvalidatesTheValidator() throws Exception {
    Item item = seedItem();
    String etag = etagOf(getDetail(item.getId(), null));

    mockMvc.perform(multipart("/api/items/{id}/photos", item.getId())
            .file(new MockMultipartFile("file", "camera.png", "image/png", PNG_BYTES))
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk());

    MvcResult after = getDetail(item.getId(), etag);
    assertThat(after.getResponse().getStatus()).isEqualTo(200);
    assertThat(etagOf(after)).isNotEqualTo(etag);
  }

  @Test
  void listEndpointStaysValidatorFree() throws Exception {
    seedItem();
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(HttpHeaders.ETAG));
  }

  @Test
  void unknownIdStillAnswers404Envelope() throws Exception {
    mockMvc.perform(get("/api/items/{id}", 999_999L)
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }
}
