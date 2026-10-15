package com.toni.marketplace.dispute;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Buyer dispute opening (backlog #169). Authenticated like every order
 * route (the security chain's anyRequest rule; anonymous callers get
 * the 401 envelope); the buyer scoping lives in {@link DisputeService}.
 * The ADMIN resolution endpoints live on {@link AdminDisputeController}.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "orders", description = "Buyer orders and the mock-PSP payment lifecycle")
@RequestMapping("/api/orders")
public class DisputeController {

  private final DisputeService disputeService;

  public DisputeController(DisputeService disputeService) {
    this.disputeService = disputeService;
  }

  /**
   * Opens a dispute on a PAID or COMPLETED order: the order moves to
   * DISPUTED (frozen — pay/cancel/complete/refund all answer 422 until
   * an ADMIN resolves it) and no money moves. Only the order's buyer
   * may dispute (403 otherwise); only inside the dispute window
   * ({@code app.disputes.window}, default 7 days from the transition —
   * outside it 422); opening while a dispute is already open returns
   * that dispute unchanged.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
          description = "validation failure — reason missing/blank or over 500 characters (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not the order's buyer (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown order id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
          description = "version conflict with a concurrent transition (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "order is not PAID/COMPLETED, the dispute window has closed, or a resolved dispute already exists (envelope)")})
  @PostMapping("/{id}/dispute")
  public ApiResponse<DisputeDto> dispute(@PathVariable Long id,
                                         @Valid @RequestBody DisputeCreateRequest request) {
    Long buyerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(disputeService.openDispute(buyerId, id, request.reason()));
  }
}
