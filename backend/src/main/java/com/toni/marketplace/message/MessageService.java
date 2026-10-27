package com.toni.marketplace.message;

import com.toni.marketplace.auth.UserBlockRepository;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.ImageStorageService;
import com.toni.marketplace.item.ItemRepository;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.web.multipart.MultipartFile;
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
  private final UserBlockRepository blocks;
  private final MessageProperties messageProps;
  private final Clock clock;
  private final ImageStorageService images;

  public MessageService(MessageRepository messages, ItemRepository items,
                        UserRepository users, UserBlockRepository blocks,
                        MessageProperties messageProps, Clock clock,
                        ImageStorageService images) {
    this.messages = messages;
    this.items = items;
    this.users = users;
    this.blocks = blocks;
    this.messageProps = messageProps;
    this.clock = clock;
    this.images = images;
  }

  /** Clock with a system fallback, mirroring ItemService's pattern. */
  private Clock clock() {
    return clock != null ? clock : Clock.systemUTC();
  }

  /** Edit window with the documented 15-minute default as fallback. */
  private Duration editWindow() {
    return messageProps != null && messageProps.getEditWindow() != null
        ? messageProps.getEditWindow() : Duration.ofMinutes(15);
  }

  @Transactional
  public MessageDto send(Long senderId, Long itemId, Long receiverId, String body) {
    checkSendGuards(senderId, itemId, receiverId);
    Message saved = messages.save(new Message(itemId, senderId, receiverId, body));
    return MessageDto.from(saved);
  }

  /**
   * Sends a message with one optional photo attachment (backlog #245).
   * The JSON send's guards run first and unchanged — a photo must never
   * widen who may message whom — and only then is the file validated
   * and stored through {@link ImageStorageService} (allowlisted image
   * types, 5 MB cap, magic-byte gate, JPEG/PNG metadata stripping, the
   * listing-photo rules exactly), so a rejected send leaves no file
   * behind and a rejected file leaves no row behind. The stored public
   * {@code /uploads/<uuid>.<ext>} path is what the row references; the
   * thread renders it through the #233 photo fallback.
   *
   * <p>Multipart requests carry no Jakarta-validated DTO, so the body
   * rules the JSON path gets from {@code MessageCreateRequest} are
   * checked here instead: blank or over-2000-character bodies are 400.
   * A missing {@code file} part is a 400 from Spring before this method
   * is reached (the controller's {@code @RequestParam} is required).
   */
  @Transactional
  public MessageDto sendWithPhoto(Long senderId, Long itemId, Long receiverId,
                                  String body, MultipartFile photo) {
    if (body == null || body.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body must not be blank");
    }
    if (body.length() > Message.MAX_BODY_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "body is too long (max 2000 characters)");
    }
    checkSendGuards(senderId, itemId, receiverId);
    String photoUrl = images.store(photo);
    Message saved = messages.save(new Message(itemId, senderId, receiverId, body, photoUrl));
    return MessageDto.from(saved);
  }

  /**
   * The send guards shared by the JSON send and the photo send (backlog
   * #245): existence, not-self, the #112 participant rule and the #187
   * block rule, in that order. The answers are identical on both paths
   * on purpose — the attachment variant must not leak a different
   * oracle for a blocked or non-participant sender.
   */
  private void checkSendGuards(Long senderId, Long itemId, Long receiverId) {
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
    // Block check (backlog #187): a user the receiver has blocked cannot
    // POST to the blocker. The answer is the participant guard's exact
    // 403 copy — never "you are blocked" — so the blocked sender learns
    // nothing a non-participant would not also learn. Existing threads
    // stay readable (the read path is untouched); only new sends stop.
    if (blocks != null && blocks.existsByBlockerIdAndBlockedId(receiverId, senderId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "not a participant in this conversation");
    }
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
    return messages.countByReceiverIdAndReadAtIsNullAndDeletedAtIsNull(userId);
  }

  /**
   * Edits one of the caller's own messages (backlog #206).
   *
   * <ul>
   *   <li>404 — no message with that id (checked before ownership, so
   *   the endpoint is not an existence oracle beyond what the thread
   *   read already exposes to participants).</li>
   *   <li>403 — the caller is not the sender. Only the sender may edit;
   *   the receiver, the listing's seller and ADMIN get no edit right.</li>
   *   <li>422 — the message is already deleted, or the edit window
   *   ({@code app.messages.edit-window}, default 15 minutes from
   *   {@code createdAt}) has closed. The boundary is inclusive: an edit
   *   landing exactly at {@code createdAt + window} is accepted.</li>
   * </ul>
   *
   * <p>An edit replaces the body and stamps {@code editedAt} from the
   * injected {@link Clock}; {@code createdAt} never moves, so thread
   * order and the #41 pagination contract are untouched. Sends fire no
   * notification in this codebase and edits fire none either — there is
   * deliberately no notification seam on this path to re-fire.
   */
  @Transactional
  public MessageDto edit(Long userId, Long messageId, String newBody) {
    Message message = messages.findById(messageId).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "message not found"));
    if (!message.getSenderId().equals(userId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "only the sender can edit this message");
    }
    if (message.isDeleted()) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "a deleted message cannot be edited");
    }
    Instant now = clock().instant();
    if (now.isAfter(message.getCreatedAt().plus(editWindow()))) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "the edit window for this message has closed");
    }
    message.edit(newBody, now);
    return MessageDto.from(message);
  }

  /**
   * Soft-deletes one of the caller's own messages (backlog #206):
   * sender-only (403 otherwise, 404 on an unknown id, mirroring
   * {@link #edit}). The row is kept — {@code deletedAt} is stamped and
   * the stored body is replaced by the tombstone — so thread order and
   * the other party's context survive, while the unread count stops
   * counting the row at once (the count query excludes deleted rows).
   * Deleting an already-deleted message is an idempotent 200 returning
   * the tombstone unchanged. Delete is not windowed: only edits are.
   */
  @Transactional
  public MessageDto delete(Long userId, Long messageId) {
    Message message = messages.findById(messageId).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "message not found"));
    if (!message.getSenderId().equals(userId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "only the sender can delete this message");
    }
    message.softDelete(clock().instant());
    return MessageDto.from(message);
  }
}
