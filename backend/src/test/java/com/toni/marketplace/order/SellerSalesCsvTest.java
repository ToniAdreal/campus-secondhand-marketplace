package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the sales-export CSV writer (backlog #159): the
 * header/CRLF shape, the spreadsheet formula-injection neutralization
 * (cells starting with {@code =}, {@code +}, {@code -}, {@code @} are
 * quote-prefixed — listing titles are user input), and RFC 4180 quoting
 * for commas, quotes and newlines. The endpoint's scoping/window/status
 * behaviour is pinned by {@link SellerSalesExportTest}.
 */
class SellerSalesCsvTest {

  private static SellerSalesCsv.Row row(String title, String captureId, String refundId) {
    return new SellerSalesCsv.Row(7L, 3L, title, 9L, OrderStatus.PAID, 12_500L,
        Instant.parse("2026-01-10T12:00:00Z"), captureId, refundId);
  }

  @Test
  void emptyRows_renderHeaderOnlyWithCrlf() {
    assertThat(SellerSalesCsv.render(List.of()))
        .isEqualTo(SellerSalesCsv.HEADER + "\r\n");
  }

  @Test
  void row_rendersAllColumns_andNullReferencesAsEmpty() {
    String csv = SellerSalesCsv.render(List.of(row("Film camera", "cap_mock_1", null)));
    assertThat(csv).isEqualTo(SellerSalesCsv.HEADER + "\r\n"
        + "7,3,Film camera,9,PAID,12500,2026-01-10T12:00:00Z,cap_mock_1,\r\n");
  }

  @Test
  void formulaLeadingTitles_areQuotePrefixed() {
    for (String title : List.of("=2+5", "+SUM(A1:A2)", "-2+5", "@cmd")) {
      assertThat(SellerSalesCsv.text(title)).isEqualTo("'" + title);
    }
    // A formula character anywhere but the first position is inert text.
    assertThat(SellerSalesCsv.text("Camera = nice")).isEqualTo("Camera = nice");
  }

  @Test
  void commaQuoteAndNewlineFields_areRfc4180Quoted() {
    assertThat(SellerSalesCsv.text("Lens, mint")).isEqualTo("\"Lens, mint\"");
    assertThat(SellerSalesCsv.text("Say \"hi\"")).isEqualTo("\"Say \"\"hi\"\"\"");
    assertThat(SellerSalesCsv.text("line1\nline2")).isEqualTo("\"line1\nline2\"");
  }

  @Test
  void formulaTitleWithComma_isNeutralizedThenQuoted() {
    // Prefix first ('=...), then quoting sees the comma and wraps.
    assertThat(SellerSalesCsv.text("=SUM(A1,A2)")).isEqualTo("\"'=SUM(A1,A2)\"");
  }
}
