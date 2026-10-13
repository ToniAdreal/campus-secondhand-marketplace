package com.toni.marketplace.order;

import java.time.Instant;
import java.util.List;

/**
 * Hand-rolled CSV rendering for the seller sales export (backlog #159) —
 * no new dependency, the format is small enough to pin by unit tests.
 *
 * <p>Two contract points are security-relevant, not cosmetic:
 * <ul>
 *   <li><b>Spreadsheet formula injection:</b> a text cell whose first
 *       character is {@code =}, {@code +}, {@code -} or {@code @} is
 *       prefixed with a single quote before quoting, so a listing title
 *       like {@code =2+5} opens in a spreadsheet as inert text, never as
 *       a formula. Listing titles are user input; the PSP references and
 *       the status are server-generated but pass through the same
 *       neutralizer for uniformity.</li>
 *   <li><b>RFC 4180 quoting:</b> a field containing a comma, a double
 *       quote, CR or LF is wrapped in double quotes with inner quotes
 *       doubled; records are CRLF-separated, header included.</li>
 * </ul>
 *
 * <p>Columns mirror the order row: ids, the listing title as it stands
 * when the export runs (the money column is the {@code amountCents}
 * snapshot, so a later price edit never rewrites it — the same rule the
 * sales summary documents), status, creation instant in ISO-8601, and
 * the mock PSP references (#114), empty until the transition that mints
 * them has happened.
 */
final class SellerSalesCsv {

  static final String HEADER =
      "order_id,item_id,item_title,buyer_id,status,amount_cents,created_at,capture_id,refund_id";

  private SellerSalesCsv() {}

  /** One export row, projected from an {@link Order} inside the service transaction. */
  record Row(long orderId, long itemId, String itemTitle, long buyerId,
             OrderStatus status, long amountCents, Instant createdAt,
             String captureId, String refundId) {

    static Row from(Order order) {
      return new Row(
          order.getId(),
          order.getItem().getId(),
          order.getItem().getTitle(),
          order.getBuyerId(),
          order.getStatus(),
          order.getAmountCents(),
          order.getCreatedAt(),
          order.getCaptureId(),
          order.getRefundId());
    }
  }

  static String render(List<Row> rows) {
    StringBuilder out = new StringBuilder(HEADER).append("\r\n");
    for (Row row : rows) {
      out.append(row.orderId()).append(',')
          .append(row.itemId()).append(',')
          .append(text(row.itemTitle())).append(',')
          .append(row.buyerId()).append(',')
          .append(row.status().name()).append(',')
          .append(row.amountCents()).append(',')
          .append(row.createdAt()).append(',')
          .append(text(row.captureId())).append(',')
          .append(text(row.refundId()))
          .append("\r\n");
    }
    return out.toString();
  }

  /** Formula-neutralizes, then RFC 4180-quotes, one text cell; null renders empty. */
  static String text(String raw) {
    if (raw == null || raw.isEmpty()) {
      return "";
    }
    String value = raw;
    char first = value.charAt(0);
    if (first == '=' || first == '+' || first == '-' || first == '@') {
      value = "'" + value;
    }
    if (value.indexOf(',') < 0 && value.indexOf('"') < 0
        && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) {
      return value;
    }
    return '"' + value.replace("\"", "\"\"") + '"';
  }
}
