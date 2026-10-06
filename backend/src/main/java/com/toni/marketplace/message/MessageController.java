package com.toni.marketplace.message;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
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
   * 400 {@code {code,message,data}} envelope.
   */
  @PostMapping
  public ApiResponse<MessageDto> send(@Valid @RequestBody MessageCreateRequest request) {
    Long senderId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(messageService.send(
        senderId, request.itemId(), request.receiverId(), request.body()));
  }

  /**
   * Reads the caller's conversation thread for one listing, oldest first.
   * Only participants (sender or receiver of at least one message) may read:
   * non-participants get 403; an unknown listing is 404.
   */
  @GetMapping
  public ApiResponse<List<MessageDto>> thread(@RequestParam Long itemId) {
    Long userId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(messageService.thread(userId, itemId));
  }
}
