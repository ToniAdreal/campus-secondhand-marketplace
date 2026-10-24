package com.toni.marketplace.order;

import com.toni.marketplace.common.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seller-facing payout reads (backlog #221). Strictly scoped to the
 * caller's {@code sellerId} — no ADMIN bypass, mirroring
 * {@link SellerOrderController} — and anonymous callers get the JSON 401
 * envelope from the entry point (no rule change was needed: everything
 * here requires a valid Bearer token via
 * {@code SecurityConfig}'s {@code anyRequest().authenticated()}).
 * The literal {@code /totals} path wins over no other route here — there
 * is no single-payout read — so path matching is unambiguous.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "seller-payouts", description = "Seller payout ledger")
@RequestMapping("/api/seller/payouts")
public class SellerPayoutController {

  private final SellerPayoutService payoutService;

  public SellerPayoutController(SellerPayoutService payoutService) {
    this.payoutService = payoutService;
  }

  /**
   * Lists the caller's payout rows, newest first. A seller with no
   * completed orders gets an empty page, not an error, mirroring the
   * seller order list's empty-page decision.
   */
  @GetMapping
  public ApiResponse<Page<SellerPayoutDto>> list(
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    Long sellerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(payoutService.listForSeller(sellerId, pageable));
  }

  /**
   * Totals over the caller's payout ledger: per-status counts and sums,
   * the compensating-adjustment total, and the net owed. Reconciles with
   * the #108 sales summary as documented on {@link SellerPayoutDto}.
   */
  @GetMapping("/totals")
  public ApiResponse<SellerPayoutTotalsDto> totals() {
    Long sellerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(payoutService.totalsForSeller(sellerId));
  }
}
