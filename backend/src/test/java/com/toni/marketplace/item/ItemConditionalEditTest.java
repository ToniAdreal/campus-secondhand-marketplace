package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conditional listing edit via {@code If-Match} (backlog #425): {@code PATCH
 * /api/items/{id}} accepts the strong ETag the detail GET serves (#166) and
 * compares it strongly (RFC 9110 §13.1.1) — a matching validator applies the
 * edit, a stale one answers 412 in the envelope and applies nothing, a
 * missing header keeps the unconditional behaviour. The check runs inside
 * the edit's transaction, before any mutation.
 *
 * <p>Transaction discipline mirrors {@link ItemConditionalGetTest}: every
 * test rolls back via method-level {@code @Transactional} (the edit path
 * uses no REQUIRES_NEW seam, so no test needs to commit).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ItemConditionalEditTest {

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

  @AfterEach
  void cleanUp() {
    itemRepo.deleteAll();
    users.deleteAll();
  }

  private User seller;
  private String sellerToken;
  private String buyerToken;

  @BeforeEach
  void createUsersAndTokens() {
    seller = users.save(new User("ifmatch-seller", "ifmatch-seller@example.com",
        passwords.encode("seller-password")));
    User buyer = users.save(new User("ifmatch-buyer", "ifmatch-buyer@example.com",
        passwords.encode("buyer-password")));
    sellerToken = jwt.createTokenPair(seller).accessToken();
    buyerToken = jwt.createTokenPair(buyer).accessToken();
  }

  private Item seedItem() {
    return itemRepo.save(new Item("Used ThinkPad T480s", "good battery", 25000L, seller.getId()));
  }

  /** The current strong ETag of the listing, read through the detail GET. */
  private String etagOf(Long id) throws Exception {
    MvcResult result = mockMvc.perform(get("/api/items/{id}", id)
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andReturn();
    String etag = result.getResponse().getHeader(HttpHeaders.ETAG);
    assertThat(etag).isNotBlank();
    return etag;
  }

  private void patchTitle(Long id, String token, String ifMatch, String title)
      throws Exception {
    var request = patch("/api/items/{id}", id)
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\"" + title + "\"}");
    if (ifMatch != null) {
      request = request.header(HttpHeaders.IF_MATCH, ifMatch);
    }
    mockMvc.perform(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.title").value(title));
  }

  @Test
  @Transactional
  void matchingValidatorAppliesTheEdit() throws Exception {
    Item item = seedItem();
    String etag = etagOf(item.getId());

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .header(HttpHeaders.IF_MATCH, etag)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"ThinkPad T480s — upgraded\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.title").value("ThinkPad T480s — upgraded"));
  }

  @Test
  @Transactional
  void staleValidatorAnswers412EnvelopeAndAppliesNothing() throws Exception {
    Item item = seedItem();
    String etag = etagOf(item.getId());

    patchTitle(item.getId(), sellerToken, etag, "ThinkPad T480s — first edit");

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .header(HttpHeaders.IF_MATCH, etag)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"stale overwrite\"}"))
        .andExpect(status().isPreconditionFailed())
        .andExpect(jsonPath("$.code").value(412))
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(jsonPath("$.data").isEmpty());

    mockMvc.perform(get("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.title").value("ThinkPad T480s — first edit"));
  }

  @Test
  @Transactional
  void missingHeaderKeepsTodaysBehaviour() throws Exception {
    Item item = seedItem();

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"ThinkPad T480s — no validator\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.title").value("ThinkPad T480s — no validator"));
  }

  @Test
  @Transactional
  void etagChangesAfterSuccessfulConditionalEdit() throws Exception {
    Item item = seedItem();
    String before = etagOf(item.getId());

    patchTitle(item.getId(), sellerToken, before, "ThinkPad T480s — edited");

    String after = etagOf(item.getId());
    assertThat(after).isNotEqualTo(before);
    // The old validator no longer matches the detail read either.
    mockMvc.perform(get("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .header(HttpHeaders.IF_NONE_MATCH, before))
        .andExpect(status().isOk());
  }

  @Test
  @Transactional
  void weakValidatorNeverMatchesIfMatch() throws Exception {
    Item item = seedItem();
    String etag = etagOf(item.getId());

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .header(HttpHeaders.IF_MATCH, "W/" + etag)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"weak overwrite\"}"))
        .andExpect(status().isPreconditionFailed())
        .andExpect(jsonPath("$.code").value(412));
  }

  @Test
  @Transactional
  void starMatchesAnExistingListing() throws Exception {
    Item item = seedItem();

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .header(HttpHeaders.IF_MATCH, "*")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"ThinkPad T480s — star edit\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.title").value("ThinkPad T480s — star edit"));
  }

  @Test
  @Transactional
  void nonOwnerStillAnswers403BeforeThePrecondition() throws Exception {
    Item item = seedItem();
    String etag = etagOf(item.getId());

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + buyerToken)
            .header(HttpHeaders.IF_MATCH, etag)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"buyer overwrite\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  @Test
  @Transactional
  void unknownIdAnswers404EvenWithStarValidator() throws Exception {
    mockMvc.perform(patch("/api/items/{id}", 999_999L)
            .header("Authorization", "Bearer " + sellerToken)
            .header(HttpHeaders.IF_MATCH, "*")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"ghost edit\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }
}
