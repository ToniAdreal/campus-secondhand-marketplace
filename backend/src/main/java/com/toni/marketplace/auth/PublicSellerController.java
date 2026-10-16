package com.toni.marketplace.auth;

import com.toni.marketplace.common.ApiResponse;
import com.toni.marketplace.item.ItemDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public seller profile + storefront (backlog #174). Authenticated —
 * this app has no anonymous reads beyond the auth/actuator set, and the
 * security chain's {@code anyRequest().authenticated()} rule already
 * covers {@code /api/users/**} (the anonymous sweep pins both routes
 * at 401). There is deliberately no {@code /api/users/{id}} bare read:
 * the profile DTO is the only public shape of a user.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "users", description = "Public seller profiles")
@RequestMapping("/api/users/{id}")
public class PublicSellerController {

  private final SellerProfileService sellerProfileService;

  public PublicSellerController(SellerProfileService sellerProfileService) {
    this.sellerProfileService = sellerProfileService;
  }

  /**
   * The seller's public profile: username, member-since, live-listing
   * count and completed-sales count — nothing else (the DTO's exact
   * key set is pinned by test). Unknown or disabled sellers are 404.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "public seller profile (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown seller, or a disabled account (envelope)")})
  @GetMapping("/profile")
  public ApiResponse<PublicSellerDto> profile(@PathVariable Long id) {
    return ApiResponse.ok(sellerProfileService.profile(id));
  }

  /**
   * The seller's live storefront: AVAILABLE listings only, newest
   * first, in the browse list-view shape. Unknown or disabled sellers
   * are 404, exactly like the profile.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "the seller's AVAILABLE listings (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown seller, or a disabled account (envelope)")})
  @GetMapping("/listings")
  public ApiResponse<Page<ItemDto>> listings(
      @PathVariable Long id, @PageableDefault(size = 20) Pageable pageable) {
    return ApiResponse.ok(sellerProfileService.liveListings(id, pageable));
  }
}
