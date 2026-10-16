package com.toni.marketplace.notification;

import com.toni.marketplace.common.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's in-app notifications (backlog #157). Every route is
 * scoped to the JWT principal — there is no recipient parameter
 * anywhere, so one user's notifications can never be read or marked by
 * another. Authentication comes from the security chain's
 * {@code anyRequest().authenticated()} rule: anonymous callers get 401
 * in the envelope.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(
    name = "notifications", description = "In-app order lifecycle notifications")
@RequestMapping("/api/notifications")
public class NotificationController {

  private final NotificationService notificationService;

  public NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  /** The caller's notifications, newest first, paginated (global size cap applies). */
  @GetMapping
  public ApiResponse<Page<NotificationDto>> list(
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    return ApiResponse.ok(notificationService.list(currentUserId(), pageable));
  }

  /**
   * The caller's total unread notifications (backlog #157): a bare
   * count for the nav bell — no notification bodies or order data are
   * exposed, mirroring {@code GET /api/messages/unread-count}.
   */
  @GetMapping("/unread-count")
  public ApiResponse<Long> unreadCount() {
    return ApiResponse.ok(notificationService.unreadCount(currentUserId()));
  }

  /** Marks one of the caller's own notifications read; someone else's id is 404. */
  @PostMapping("/{id}/read")
  public ApiResponse<NotificationDto> markRead(@PathVariable Long id) {
    return ApiResponse.ok(notificationService.markRead(currentUserId(), id));
  }

  /**
   * Marks all of the caller's unread notifications read in one call
   * (backlog #173); the body is the number stamped (0 on a repeat).
   * Declared before the {@code /{id}} routes so the literal segment
   * always wins over the path variable.
   */
  @PostMapping("/read-all")
  public ApiResponse<Integer> markAllRead() {
    return ApiResponse.ok(notificationService.markAllRead(currentUserId()));
  }

  /** Deletes one of the caller's own notifications; someone else's id is 404. */
  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    notificationService.delete(currentUserId(), id);
    return ApiResponse.ok(null);
  }

  private static Long currentUserId() {
    return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }
}
