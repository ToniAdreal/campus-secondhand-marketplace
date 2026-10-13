package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/seller/sales/export.csv} contract (backlog #159) — the
 * CSV counterpart of {@link SellerSalesSummaryTest}.
 *
 * <p>Pinned here:
 * <ul>
 *   <li>{@code text/csv} + {@code Content-Disposition} filename, the
 *       header row, and PAID/COMPLETED rows only (PENDING/CANCELLED/
 *       REFUNDED never export), newest first;</li>
 *   <li>strict scoping to the caller's own listings;</li>
 *   <li>the #155 window semantics: {@code [from, to)} over
 *       {@code createdAt}, an inverted window answers the 400 JSON
 *       envelope;</li>
 *   <li>a formula-looking title ({@code =2+5}) is exported neutralized
 *       and a comma/quote title is RFC 4180-quoted — the writer's unit
 *       behaviour is pinned separately in {@link SellerSalesCsvTest};</li>
 *   <li>a seller with no sales gets a header-only file; anonymous
 *       callers get the 401 envelope.</li>
 * </ul>
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link SellerSalesSummaryTest}: each test owns its data via unique
 * users and wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SellerSalesExportTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private IdempotencyKeyRepository keys;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private JdbcTemplate jdbc;

  @AfterEach
  void cleanUp() {
    keys.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser(String tag) {
    String name = tag + "-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(name, name + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item newItem(User seller, String title, long priceCents) {
    return items.save(new Item(title, "desc", priceCents, seller.getId()));
  }

  private long createOrder(User buyer, Item item) throws Exception {
    String body = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", "key-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(body, "$.data.id")).longValue();
  }

  private void pay(User buyer, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());
  }

  private void complete(User seller, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());
  }

  private void refund(User seller, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());
  }

  private void cancel(User buyer, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());
  }

  private String export(User seller) throws Exception {
    return mockMvc.perform(get("/api/seller/sales/export.csv")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
  }

  @Test
  void export_contentTypeDisposition_paidCompletedRowsOnly_newestFirst() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");

    createOrder(buyer, newItem(seller, "Pending camera", 10_000L)); // PENDING — excluded
    long paid = createOrder(buyer, newItem(seller, "Paid camera", 20_000L));
    pay(buyer, paid); // PAID
    long completed = createOrder(buyer, newItem(seller, "Completed lens", 30_000L));
    pay(buyer, completed);
    complete(seller, completed); // COMPLETED
    long refunded = createOrder(buyer, newItem(seller, "Refunded radio", 40_000L));
    pay(buyer, refunded);
    refund(seller, refunded); // REFUNDED — excluded
    long cancelled = createOrder(buyer, newItem(seller, "Cancelled desk", 50_000L));
    cancel(buyer, cancelled); // CANCELLED — excluded

    String body = mockMvc.perform(get("/api/seller/sales/export.csv")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/csv"))
        .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"seller-sales.csv\""))
        .andReturn().getResponse().getContentAsString();

    String[] lines = body.split("\r\n");
    assertThat(lines).hasSize(3); // header + PAID + COMPLETED
    assertThat(lines[0]).isEqualTo(SellerSalesCsv.HEADER);
    // Newest first: the COMPLETED order was created after the PAID one.
    assertThat(lines[1]).contains("Completed lens").contains(",COMPLETED,30000,")
        .contains("cap_mock_");
    assertThat(lines[2]).contains("Paid camera").contains(",PAID,20000,")
        .contains("cap_mock_");
    assertThat(body).doesNotContain("Pending camera", "Refunded radio", "Cancelled desk");
  }

  @Test
  void export_isScopedToOwnListingsOnly() throws Exception {
    User sellerA = newUser("sellerA");
    User sellerB = newUser("sellerB");
    User buyer = newUser("buyer");
    long own = createOrder(buyer, newItem(sellerA, "Own camera", 11_000L));
    pay(buyer, own);
    long other = createOrder(buyer, newItem(sellerB, "Other camera", 99_000L));
    pay(buyer, other);

    String body = export(sellerA);
    assertThat(body).contains("Own camera").doesNotContain("Other camera");
  }

  @Test
  void export_window_fromInclusiveToExclusive_andInvertedIs400() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long january = createOrder(buyer, newItem(seller, "January camera", 10_000L));
    pay(buyer, january);
    long february = createOrder(buyer, newItem(seller, "February camera", 20_000L));
    pay(buyer, february);
    jdbc.update("update orders set created_at = ? where id = ?",
        Timestamp.from(Instant.parse("2026-01-10T12:00:00Z")), january);
    jdbc.update("update orders set created_at = ? where id = ?",
        Timestamp.from(Instant.parse("2026-02-10T12:00:00Z")), february);

    String body = mockMvc.perform(get("/api/seller/sales/export.csv")
            .param("from", "2026-01-10T12:00:00Z")
            .param("to", "2026-02-10T12:00:00Z")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    // [from, to): the January row sits exactly on `from` (in), the
    // February row exactly on `to` (out).
    assertThat(body).contains("January camera").doesNotContain("February camera");

    mockMvc.perform(get("/api/seller/sales/export.csv")
            .param("from", "2026-02-10T12:00:00Z")
            .param("to", "2026-01-10T12:00:00Z")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("from must be before to"));
  }

  @Test
  void export_formulaTitleNeutralized_andCommaQuoteTitleQuoted() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long formula = createOrder(buyer, newItem(seller, "=2+5", 10_000L));
    pay(buyer, formula);
    long quoted = createOrder(buyer, newItem(seller, "Lens, \"mint\"", 20_000L));
    pay(buyer, quoted);

    String body = export(seller);
    assertThat(body).contains("'=2+5").doesNotContain(",=2+5,");
    assertThat(body).contains("\"Lens, \"\"mint\"\"\"");
  }

  @Test
  void export_sellerWithNoSales_headerOnlyFile() throws Exception {
    User buyerOnly = newUser("buyerOnly");
    assertThat(export(buyerOnly)).isEqualTo(SellerSalesCsv.HEADER + "\r\n");
  }

  @Test
  void export_anonymous_isRejected401() throws Exception {
    mockMvc.perform(get("/api/seller/sales/export.csv"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
