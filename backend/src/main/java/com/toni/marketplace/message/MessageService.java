package com.toni.marketplace.message;

import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.ItemRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * v1 offline messaging: send + per-listing thread read.
 *
 * <p>Send guards:
 * <ul>
 *   <li>404 — the listing does not exist, or the receiver does not exist.</li>
 *   <li>422 — messaging yourself: a conversation is between two distinct
 *       users, and "self-talk" rows would leak into the thread query in
 *       confusing ways.</li>
 * </ul>
 * The sender is the JWT principal, never a request field.
 *
 * <p>Thread-read privacy: {@code GET /api/messages?itemId=} returns only
 * messages where the caller is sender or receiver. A caller who has not
 * participated in the listing's thread gets 403 — not an empty list — so a
 * third party cannot distinguish "no conversation" from "none of your
 * business" and gets no implicit thread-membership oracle. (404 on an
 * unknown listing precedes the check, matching the rest of the API.)
 */
@Service
public class MessageService {

  private final MessageRepository messages;
  private final ItemRepository items;
  private final UserRepository users;

  public MessageService(MessageRepository messages, ItemRepository items,
                        UserRepository users) {
    this.messages = messages;
    this.items = items;
    this.users = users;
  }

  @Transactional
  public MessageDto send(Long senderId, Long itemId, Long receiverId, String body) {
    if (!items.existsById(itemId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found");
    }
    if (!users.existsById(receiverId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "receiver not found");
    }
    if (senderId.equals(receiverId)) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "cannot send a message to yourself");
    }
    Message saved = messages.save(new Message(itemId, senderId, receiverId, body));
    return MessageDto.from(saved);
  }

  @Transactional(readOnly = true)
  public List<MessageDto> thread(Long userId, Long itemId) {
    if (!items.existsById(itemId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found");
    }
    List<MessageDto> thread = messages.findThreadByItemAndParticipant(itemId, userId)
        .stream().map(MessageDto::from).toList();
    if (thread.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "not a participant in this conversation");
    }
    return thread;
  }
}
