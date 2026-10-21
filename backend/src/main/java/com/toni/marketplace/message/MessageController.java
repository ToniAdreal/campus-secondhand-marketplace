package com.toni.marketplace.message;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
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

@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "messages", description = "Offline messages between listing participants")
@RequestMapping("/api/messages")
public class MessageController {

  private final MessageService messageService;

  public MessageController(MessageService messageService) {
    this.messageService = messageService;
  }

  /**
   * Sends one message about a listing. The sender is the JWT principal;
   * {@code itemId}, {@code receiverId} and the text {@code body} come from
   * the request. Jakarta validation turns blank/overlong bodies into the
   * 400 {@code {code,message,data}} envelope. Pairing rule (backlog
   * #112): buyer → seller opens a thread; seller → buyer is a reply and
   * needs an existing thread on the listing; any other pairing is 403.
   */
  @PostMapping
  public ApiResponse<MessageDto> send(@Valid @RequestBody MessageCreateRequest request) {
    Long senderId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(messageService.send(
        senderId, request.itemId(), request.receiverId(), request.body()));
  }

  /**
   * Reads one page of the caller's conversation thread for one listing.
   * Page 0 is the <em>newest</em> page (default {@code size=20}, capped at
   * 50); messages are chronological within the page. Only participants
   * (sender or receiver of at least one message) may read: non-participants
   * get 403; an unknown listing is 404.
   */
  @GetMapping
  public ApiResponse<Page<MessageDto>> thread(
      @RequestParam Long itemId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Long userId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(messageService.thread(userId, itemId, page, size));
  }

  /**
   * The caller's total unread messages across all listings (backlog
   * #106): a bare count for the nav badge — no message bodies, senders
   * or listing ids are exposed. Authenticated only (anonymous → 401
   * envelope, like every other {@code /api/messages} route).
   */
  @GetMapping("/unread-count")
  public ApiResponse<Long> unreadCount() {
    Long userId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(messageService.unreadCount(userId));
  }

  /**
   * Edits one of the caller's own messages (backlog #206). Sender-only
   * (403 otherwise, 404 on an unknown id) and only inside the edit
   * window ({@code app.messages.edit-window}, default 15 minutes from
   * send — outside it 422, as is editing a deleted message). Jakarta
   * validation turns blank/overlong replacement bodies into the 400
   * envelope, exactly like the send. Covered by the #76 per-user
   * message rate-limit bucket, like the send.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
          description = "validation failure — body blank or over 2000 characters (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not the message's sender (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown message id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "edit window closed, or the message is deleted (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
          description = "message rate limit (envelope)")})
  @PutMapping("/{id}")
  public ApiResponse<MessageDto> edit(@PathVariable Long id,
                                      @Valid @RequestBody MessageEditRequest request) {
    Long userId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(messageService.edit(userId, id, request.body()));
  }

  /**
   * Soft-deletes one of the caller's own messages (backlog #206).
   * Sender-only (403 otherwise, 404 on an unknown id). The row is
   * kept as a tombstone so thread order survives; deleting an
   * already-deleted message is an idempotent 200. Covered by the #76
   * per-user message rate-limit bucket, like the send.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not the message's sender (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown message id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
          description = "message rate limit (envelope)")})
  @DeleteMapping("/{id}")
  public ApiResponse<MessageDto> delete(@PathVariable Long id) {
    Long userId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(messageService.delete(userId, id));
  }
}
