package com.toni.marketplace.message;

import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.ItemRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
 *   <li>403 — the pair is not a conversation for this listing (backlog
 *       #112, the seller-reply item): a send is only allowed buyer →
 *       seller (any non-seller may open a thread with the listing's
 *       seller) or seller → buyer where that buyer already has a thread
 *       on this listing. Before #112 the send checked only existence +
 *       not-self, so anyone could message anyone about someone else's
 *       listing — a spam/impersonation hole the thread read's 403 rule
 *       never had on the write side. A seller cold-messaging a user who
 *       never wrote about the listing is rejected for the same reason:
 *       replies are derived from an existing thread, never free-form.</li>
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

  /** Hard cap on a thread page: an unbounded thread read is a response-size
   * and memory DoS vector, so oversized requests are clamped, not honored. */
  public static final int MAX_THREAD_PAGE_SIZE = 50;

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
    var item = items.findById(itemId).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    if (!users.existsById(receiverId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "receiver not found");
    }
    if (senderId.equals(receiverId)) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "cannot send a message to yourself");
    }
    // Participant guard (backlog #112): the only legitimate pairs on a
    // listing are buyer → its seller, and the seller replying inside a
    // thread a buyer already opened on this listing. Everything else —
    // messaging a stranger about someone else's listing, or a seller
    // cold-messaging a user with no thread here — is 403, mirroring the
    // thread read's "not a participant" answer.
    Long sellerId = item.getSellerId();
    boolean buyerToSeller = receiverId.equals(sellerId) && !senderId.equals(sellerId);
    boolean sellerReply = senderId.equals(sellerId)
        && messages.existsThreadBetween(itemId, senderId, receiverId);
    if (!buyerToSeller && !sellerReply) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "not a participant in this conversation");
    }
    Message saved = messages.save(new Message(itemId, senderId, receiverId, body));
    return MessageDto.from(saved);
  }

  /**
   * One page of the caller's conversation thread for a listing.
   *
   * <p>Page 0 is the <em>newest</em> page; within a page the messages are
   * chronological, so a chat UI can render each page top-to-bottom and stitch
   * older pages above the current one. {@code size} is clamped to
   * [1, {@value #MAX_THREAD_PAGE_SIZE}] and negative pages to 0 — oversized
   * reads would turn one long thread into an unbounded response.
   *
   * <p>The sort is deliberately not client-overridable: thread order is a
   * product contract, not a query preference.
   *
   * <p>Read state (backlog #106): a successful thread view stamps every
   * still-unread row on this listing addressed to the caller, in this
   * same transaction — the caller's own sent rows are never stamped by
   * their view, and the 403 path stamps nothing (the participant check
   * runs first). The stamp happens before DTO mapping, and the unread
   * rows are loaded as managed entities, so the returned page already
   * shows the fresh {@code readAt}.
   */
  @Transactional
  public Page<MessageDto> thread(Long userId, Long itemId, int page, int size) {
    if (!items.existsById(itemId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found");
    }
    PageRequest pageable = PageRequest.of(
        Math.max(page, 0),
        Math.min(Math.max(size, 1), MAX_THREAD_PAGE_SIZE),
        Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    Page<Message> fetched =
        messages.findThreadPageByItemAndParticipant(itemId, userId, pageable);
    if (fetched.getTotalElements() == 0) {
      // totalElements == 0 means the caller has no message on this listing,
      // which is exactly the old "not a participant" condition — it holds on
      // every page, so an out-of-range page for a real participant is a 200
      // empty page, while a stranger on any page gets 403.
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "not a participant in this conversation");
    }
    Instant now = Instant.now();
    for (Message unread : messages.findUnreadByItemAndReceiver(itemId, userId)) {
      unread.markRead(now);
    }
    List<MessageDto> chronological = new ArrayList<>(
        fetched.stream().map(MessageDto::from).toList());
    Collections.reverse(chronological);
    return new PageImpl<>(chronological, pageable, fetched.getTotalElements());
  }

  /**
   * The caller's total unread messages across all listings (backlog
   * #106) — a count only; no message body or sender data is exposed.
   */
  @Transactional(readOnly = true)
  public long unreadCount(Long userId) {
    return messages.countByReceiverIdAndReadAtIsNull(userId);
  }
}
