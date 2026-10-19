package com.toni.marketplace.item;

import com.toni.marketplace.common.ApiResponse;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's recently-viewed listings (backlog #195). The route is
 * scoped to the JWT principal — there is no user-id parameter, so one
 * user's browsing trail can never be read by another. Recording
 * happens on the listing detail read itself (see
 * {@link ItemService#getItemView}); this controller is read-only.
 * Authentication comes from the security chain's
 * {@code anyRequest().authenticated()} rule: anonymous callers get
 * 401 in the envelope.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(
    name = "recently-viewed", description = "Recently viewed listings")
@RequestMapping("/api/users/me/recently-viewed")
public class RecentlyViewedController {

  private final RecentlyViewedService recentlyViewedService;

  public RecentlyViewedController(RecentlyViewedService recentlyViewedService) {
    this.recentlyViewedService = recentlyViewedService;
  }

  /**
   * The caller's trail, most-recent first, capped at
   * {@link RecentlyViewedService#RECENT_LIMIT}, in the list-view
   * projection — listings still AVAILABLE only; viewed listings that
   * have since sold/expired/been deleted are silently omitted.
   */
  @GetMapping
  public ApiResponse<List<ItemDto>> list() {
    return ApiResponse.ok(recentlyViewedService.listRecentlyViewed(currentUserId()));
  }

  private static Long currentUserId() {
    return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }
}
