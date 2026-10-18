package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.item.Favorite;
import com.toni.marketplace.item.FavoriteRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.SavedSearch;
import com.toni.marketplace.item.SavedSearchRepository;
import com.toni.marketplace.message.Message;
import com.toni.marketplace.message.MessageRepository;
import com.toni.marketplace.order.Order;
import com.toni.marketplace.order.OrderRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
 * Self-service account data export (backlog #172):
 * {@code GET /api/users/me/export} returns the caller's own data as one
 * JSON download — profile, listings, orders as buyer and as seller,
 * messages, favorites and saved searches — each section through the
 * DTO projection its own API already shows.
 *
 * <p>Pinned here: section completeness against seeded data, the
 * field-denylist (no password hash, TOTP secret or token material ever
 * appears — neither as a field name nor as a seeded value), other
 * users' rows absent in every section, anonymous 401, the download
 * filename (user id + export date), and the DATA_EXPORTED audit row.
 * The rate-limit bucket has its own class
 * ({@code AccountDataExportRateLimitTest}), mirroring the report
 * bucket's split.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccountDataExportTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private ItemRepository items;
  @Autowired private OrderRepository orders;
  @Autowired private MessageRepository messages;
  @Autowired private FavoriteRepository favorites;
  @Autowired private SavedSearchRepository savedSearches;
  @Autowired private AuditLogRepository auditLog;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private ObjectMapper objectMapper;

  private User exporter;
  private User other;
  private User third;
  private Item exporterListing;
  private Item otherListing;
  private String exporterToken;

  @BeforeEach
  void setUp() {
    String tag = "export-" + UUID.randomUUID().toString().substring(0, 8);
    exporter = users.save(new User(tag + "-me", tag + "-me@example.com",
        passwords.encode("export-password-1")));
    // A seeded TOTP secret value: the denylist test proves even a value
    // that exists on the row never reaches the payload.
    exporter.setTotpSecret("v1:seeded-totp-secret-value-for-export-test");
    exporter = users.save(exporter);
    other = users.save(new User(tag + "-other", tag + "-other@example.com",
        passwords.encode("export-password-2")));
    third = users.save(new User(tag + "-third", tag + "-third@example.com",
        passwords.encode("export-password-3")));
    User fourth = users.save(new User(tag + "-fourth", tag + "-fourth@example.com",
        passwords.encode("export-password-4")));

    exporterListing = items.save(new Item("Exporter road bike", "the exporter's listing",
        50000L, exporter.getId()));
    otherListing = items.save(new Item("Other desk lamp", "the other user's listing",
        3000L, other.getId()));
    Item thirdListing = items.save(new Item("Third party plant", "not the exporter's",
        1500L, third.getId()));

    // The exporter as buyer (an order on the other user's listing) and
    // as seller (the other user ordered the exporter's listing).
    orders.save(new Order(otherListing, exporter.getId(), 3000L));
    orders.save(new Order(exporterListing, other.getId(), 50000L));
    // A trade between the other two users — absent from the export.
    orders.save(new Order(thirdListing, other.getId(), 1500L));

    // Messages the exporter sent and received, plus a thread between
    // two users who are neither the exporter nor the other user — it
    // must appear in neither of their exports.
    messages.save(new Message(otherListing.getId(), exporter.getId(), other.getId(),
        "exporter asks about the lamp"));
    messages.save(new Message(otherListing.getId(), other.getId(), exporter.getId(),
        "other replies to the exporter"));
    messages.save(new Message(thirdListing.getId(), third.getId(), fourth.getId(),
        "a thread neither user is part of"));

    favorites.save(new Favorite(exporter.getId(), otherListing.getId(),
        otherListing.getPriceCents()));
    favorites.save(new Favorite(other.getId(), exporterListing.getId(),
        exporterListing.getPriceCents()));

    savedSearches.save(new SavedSearch(exporter.getId(), "road bike", null, null, null,
        "newest"));
    savedSearches.save(new SavedSearch(other.getId(), "desk lamp", null, null, null,
        "newest"));

    exporterToken = jwt.createTokenPair(exporter).accessToken();
  }

  /** Mints a step-up grant (#188) for the given credentials. */
  private String confirmGrant(String token, String password) throws Exception {
    MvcResult result = mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/users/me/export/confirm")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + password + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString())
        .path("data").path("grant").asText();
  }

  private MvcResult exportAs(String token, String password) throws Exception {
    return mockMvc.perform(get("/api/users/me/export")
            .header("Authorization", "Bearer " + token)
            .header(AccountDataExportController.EXPORT_GRANT_HEADER,
                confirmGrant(token, password)))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
            org.hamcrest.Matchers.containsString(MediaType.APPLICATION_JSON_VALUE)))
        .andReturn();
  }

  private static List<String> titles(JsonNode section) {
    List<String> titles = new ArrayList<>();
    section.forEach(row -> titles.add(row.path("title").asText()));
    return titles;
  }

  private static List<String> bodies(JsonNode section) {
    List<String> bodies = new ArrayList<>();
    section.forEach(row -> bodies.add(row.path("body").asText()));
    return bodies;
  }

  @Test
  void exportDownloadsAsJsonAttachmentNamedWithUserIdAndDate() throws Exception {
    String expectedFilename = "marketplace-data-user-" + exporter.getId() + "-"
        + LocalDate.now(ZoneOffset.UTC) + ".json";
    mockMvc.perform(get("/api/users/me/export")
            .header("Authorization", "Bearer " + exporterToken)
            .header(AccountDataExportController.EXPORT_GRANT_HEADER,
                confirmGrant(exporterToken, "export-password-1")))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + expectedFilename + "\""));
  }

  @Test
  void exportCarriesEverySectionOfTheCallersOwnData() throws Exception {
    JsonNode doc = objectMapper.readTree(
        exportAs(exporterToken, "export-password-1").getResponse().getContentAsString());

    assertThat(doc.has("exportedAt")).isTrue();
    JsonNode profile = doc.path("profile");
    assertThat(profile.path("id").asLong()).isEqualTo(exporter.getId());
    assertThat(profile.path("username").asText()).isEqualTo(exporter.getUsername());
    assertThat(profile.path("email").asText()).isEqualTo(exporter.getEmail());
    assertThat(profile.path("roles").get(0).asText()).isEqualTo("USER");
    assertThat(profile.has("createdAt")).isTrue();

    // Own listings only.
    assertThat(titles(doc.path("listings")))
        .containsExactly("Exporter road bike");

    // Orders split by side: as buyer the exporter bought the lamp, as
    // seller the other user bought the bike.
    JsonNode asBuyer = doc.path("ordersAsBuyer");
    assertThat(asBuyer.size()).isEqualTo(1);
    assertThat(asBuyer.get(0).path("itemId").asLong()).isEqualTo(otherListing.getId());
    assertThat(asBuyer.get(0).path("buyerId").asLong()).isEqualTo(exporter.getId());
    JsonNode asSeller = doc.path("ordersAsSeller");
    assertThat(asSeller.size()).isEqualTo(1);
    assertThat(asSeller.get(0).path("itemId").asLong()).isEqualTo(exporterListing.getId());
    assertThat(asSeller.get(0).path("buyerId").asLong()).isEqualTo(other.getId());

    // Both directions of the exporter's thread, chronological.
    assertThat(bodies(doc.path("messages"))).containsExactly(
        "exporter asks about the lamp", "other replies to the exporter");

    // Favorites carry the saved listing through the ItemDto projection.
    assertThat(titles(doc.path("favorites"))).containsExactly("Other desk lamp");

    // Saved searches ride their live DTO, match count included.
    JsonNode searches = doc.path("savedSearches");
    assertThat(searches.size()).isEqualTo(1);
    assertThat(searches.get(0).path("keyword").asText()).isEqualTo("road bike");
    assertThat(searches.get(0).has("matchCount")).isTrue();
  }

  @Test
  void exportNeverCarriesSensitiveFieldNamesOrSeededSecretValues() throws Exception {
    String body = exportAs(exporterToken, "export-password-1").getResponse().getContentAsString();
    String lower = body.toLowerCase(Locale.ROOT);
    for (String denied : List.of(
        "password", "passwordhash", "totp", "secret", "token", "refreshtoken",
        "accesstoken", "challenge")) {
      assertThat(lower).doesNotContain(denied);
    }
    // The actual seeded credential values are absent too — the denylist
    // above pins names, this pins values.
    assertThat(body).doesNotContain(exporter.getPasswordHash());
    assertThat(body).doesNotContain("seeded-totp-secret-value-for-export-test");
  }

  @Test
  void otherUsersExportContainsNoneOfTheExportersRows() throws Exception {
    JsonNode doc = objectMapper.readTree(
        exportAs(jwt.createTokenPair(other).accessToken(), "export-password-2")
            .getResponse().getContentAsString());
    assertThat(doc.path("profile").path("id").asLong()).isEqualTo(other.getId());
    assertThat(titles(doc.path("listings"))).containsExactly("Other desk lamp");
    // The other user's export carries exactly the shared thread (both
    // directions) — and none of the third/fourth users' thread.
    assertThat(bodies(doc.path("messages"))).containsExactly(
        "exporter asks about the lamp", "other replies to the exporter");
    assertThat(doc.path("savedSearches").get(0).path("keyword").asText())
        .isEqualTo("desk lamp");
  }

  @Test
  void exportWritesOneDataExportedAuditRowForTheCaller() throws Exception {
    long before = auditLog.countByAction(AuditAction.DATA_EXPORTED);
    exportAs(exporterToken, "export-password-1");
    assertThat(auditLog.countByAction(AuditAction.DATA_EXPORTED)).isEqualTo(before + 1);
    assertThat(auditLog.searchAuditLog(exporter.getId(), AuditAction.DATA_EXPORTED,
            com.toni.marketplace.audit.AuditTargetType.USER, exporter.getId(),
            org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements())
        .isEqualTo(1);
  }

  @Test
  void anonymousExportGets401Envelope() throws Exception {
    mockMvc.perform(get("/api/users/me/export"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
