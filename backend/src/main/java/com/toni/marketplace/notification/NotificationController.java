package com.toni.marketplace.notification;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
  private final NotificationStreamService streamService;

  public NotificationController(NotificationService notificationService,
                                NotificationStreamService streamService) {
    this.notificationService = notificationService;
    this.streamService = streamService;
  }

  /**
   * Issues the one-time stream ticket (backlog #203). Authenticated —
   * the ticket is bound to the caller's id; anonymous callers get 401.
   */
  @PostMapping("/stream-ticket")
  public ApiResponse<StreamTicketResponse> streamTicket() {
    return ApiResponse.ok(new StreamTicketResponse(streamService.issueTicket(currentUserId())));
  }

  /**
   * Opens the SSE stream (backlog #203). Public at the filter chain
   * because {@code EventSource} cannot send an Authorization header —
   * the single-use ticket consumed here is the credential, and a
   * missing / reused / expired ticket is the same 401 envelope as any
   * other unauthenticated call. The access token is never accepted in
   * a URL.
   */
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@RequestParam(required = false) String ticket) {
    Long userId = streamService.consumeTicket(ticket);
    if (userId == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthorized");
    }
    return streamService.openStream(userId);
  }

  /**
   * The caller's per-type notification-preference catalogue (backlog
   * #216): every type with its label, its locked-on security flag and
   * the caller's effective state. Labels live on the backend enum —
   * this response is the settings UI's only source for them.
   */
  @GetMapping("/preferences")
  public ApiResponse<List<NotificationPreferenceDto>> preferences() {
    return ApiResponse.ok(notificationService.listPreferences(currentUserId()));
  }

  /**
   * Toggles one notification type for the caller (backlog #216).
   * Muting a security type is 422; an unknown type name is 400 (the
   * same malformed-request answer as a missing {@code enabled} flag).
   */
  @PutMapping("/preferences/{type}")
  public ApiResponse<NotificationPreferenceDto> updatePreference(
      @PathVariable String type,
      @Valid @RequestBody NotificationPreferenceUpdateRequest request) {
    NotificationType parsed;
    try {
      parsed = NotificationType.valueOf(type);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown notification type");
    }
    return ApiResponse.ok(
        notificationService.setPreference(currentUserId(), parsed, request.enabled()));
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
