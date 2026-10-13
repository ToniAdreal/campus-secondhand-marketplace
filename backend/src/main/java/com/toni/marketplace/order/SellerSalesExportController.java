package com.toni.marketplace.order;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seller sales CSV export (backlog #159): {@code GET /api/seller/sales/export.csv}.
 *
 * <p>The seller sales summary (#108) is JSON-only, so a seller keeping
 * their own books had no way to pull their sales out of the app — while
 * the audit-retention note (#118) already tells operators to export
 * before a purge, sellers had no equivalent. This endpoint streams the
 * caller's own PAID/COMPLETED sales as {@code text/csv} (hand-rolled
 * writer, see {@link SellerSalesCsv} — including the spreadsheet
 * formula-injection neutralization that is part of the contract).
 *
 * <p>The optional {@code from}/{@code to} window mirrors the ADMIN
 * settlement window (#155) exactly: ISO-8601 instants bounding
 * {@code Order.createdAt} as {@code [from, to)}, either side may be
 * omitted, and an inverted window is a 400 in the JSON envelope (the
 * error answer is JSON even though the success answer is CSV).
 *
 * <p>Security posture mirrors {@link SellerOrderController}: everything
 * here requires a valid Bearer token via {@code SecurityConfig}'s
 * {@code anyRequest().authenticated()}, anonymous callers get the JSON
 * 401 envelope, and the rows are scoped strictly to the caller's
 * {@code sellerId} in the service — no ADMIN bypass.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(
    name = "seller-sales", description = "Seller sales export")
@RequestMapping("/api/seller/sales")
public class SellerSalesExportController {

  private final OrderService orderService;

  public SellerSalesExportController(OrderService orderService) {
    this.orderService = orderService;
  }

  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "400", description = "inverted date window — from must be before to (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "401", description = "unauthenticated (envelope)")})
  @GetMapping(value = "/export.csv", produces = "text/csv")
  public ResponseEntity<String> exportCsv(
      @RequestParam(name = "from", required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam(name = "to", required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
    Long sellerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    String csv = orderService.sellerSalesCsv(sellerId, from, to);
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"seller-sales.csv\"")
        .body(csv);
  }
}
