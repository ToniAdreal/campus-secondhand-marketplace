package com.toni.marketplace.notification;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-sent-events push for in-app notifications (backlog #203).
 *
 * <p>The notification bell previously learned about events only on
 * refetch; a seller sitting on the SPA never saw an offer arrive. This
 * service keeps one {@link SseEmitter} registry per user and fans a
 * minimal event out whenever {@link NotificationService} records a row:
 * the event payload is exactly {@code {type, id}} — the notification's
 * type and row id, never body data (the row itself may name prices,
 * listings and counterparties; the stream is only a "something changed"
 * hint, and the SPA refetches the real rows over the authenticated API,
 * which stays the fallback whenever the stream is down).
 *
 * <p>Authentication: {@code EventSource} cannot set an
 * {@code Authorization} header, so the stream is opened with a one-time
 * stream ticket instead of the access token — the token never appears
 * in a URL (URLs land in proxy logs and browser history). A ticket is
 * issued by an authenticated call, is single-use, and lives
 * {@link #TICKET_TTL}; consuming it is the only way to open a stream,
 * and an unknown / reused / expired ticket is a plain 401.
 *
 * <p>Honest scope: emitters and tickets are JVM-local state (a
 * single-instance demo deployment, like the rate-limit buckets); a
 * restart drops every stream and the SPA's refetch fallback covers the
 * gap. Events are published after the recording transaction commits
 * (see {@link NotificationService}), so a rolled-back transition never
 * produces a phantom event.
 */
@Service
public class NotificationStreamService {

  /** How long an issued-but-unconsumed stream ticket stays valid. */
  static final Duration TICKET_TTL = Duration.ofSeconds(60);

  /** Server-side stream lifetime; the client reconnects with a fresh ticket. */
  static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

  private record Ticket(Long userId, Instant expiresAt) {}

  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
  private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

  @Autowired
  public NotificationStreamService(Clock clock) {
    this.clock = clock;
  }

  /**
   * Drops every expired ticket, whatever the entry point. Issue-time
   * purging alone leaves a quiet account's expired tickets in the map
   * until someone else issues one; the consume path purges too
   * (backlog #268), so a stale ticket is gone the next time any
   * ticket is presented — including an unknown one.
   */
  private void purgeExpiredTickets() {
    tickets.values().removeIf(t -> !t.expiresAt().isAfter(Instant.now(clock)));
  }

  /** Issues a single-use stream ticket for the caller (60 s to open the stream). */
  public String issueTicket(Long userId) {
    purgeExpiredTickets();
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    tickets.put(ticket, new Ticket(userId, Instant.now(clock).plus(TICKET_TTL)));
    return ticket;
  }

  /**
   * Consumes a ticket: returns its user id exactly once. An unknown,
   * already-consumed or expired ticket returns null (the controller
   * answers 401 — the three cases are indistinguishable by design).
   */
  public Long consumeTicket(String ticket) {
    purgeExpiredTickets();
    if (ticket == null) {
      return null;
    }
    Ticket found = tickets.remove(ticket);
    if (found == null || !found.expiresAt().isAfter(Instant.now(clock))) {
      return null;
    }
    return found.userId();
  }

  /** Registers a new emitter for the user and wires its cleanup callbacks. */
  public SseEmitter openStream(Long userId) {
    Set<SseEmitter> set = emitters.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>());
    Runnable[] cleanupRef = new Runnable[1];
    // Deregister synchronously when the app itself completes the emitter:
    // SseEmitter.complete() alone only notifies the MVC handler, and the
    // onCompletion callback fires later (or never, for an emitter Spring
    // never initialized) — without this override a completed emitter
    // would linger in the registry until a publish/heartbeat send failed
    // on it. The handler callbacks below stay as the backstop for client
    // disconnects and timeouts, where the app never calls complete().
    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS) {
      @Override
      public void complete() {
        cleanupRef[0].run();
        super.complete();
      }

      @Override
      public void completeWithError(Throwable ex) {
        cleanupRef[0].run();
        super.completeWithError(ex);
      }
    };
    Runnable cleanup = () -> {
      set.remove(emitter);
      if (set.isEmpty()) {
        emitters.remove(userId, set);
      }
    };
    cleanupRef[0] = cleanup;
    set.add(emitter);
    emitter.onCompletion(cleanup);
    emitter.onTimeout(cleanup);
    emitter.onError(e -> cleanup.run());
    return emitter;
  }

  /**
   * The exact event payload — keys are {@code type} and {@code id} and
   * nothing else (pinned by test): the stream carries no body data.
   */
  static Map<String, Object> eventPayload(NotificationType type, Long notificationId) {
    return Map.of("type", type.name(), "id", notificationId);
  }

  /**
   * Fans one event out to every live emitter of the recipient. A dead
   * emitter is dropped silently — delivery is best-effort by design and
   * the refetch fallback covers any loss.
   */
  public void publish(Long recipientId, NotificationType type, Long notificationId) {
    Set<SseEmitter> set = emitters.get(recipientId);
    if (set == null || set.isEmpty()) {
      return;
    }
    for (SseEmitter emitter : set) {
      try {
        emitter.send(SseEmitter.event()
            .name("notification")
            .data(eventPayload(type, notificationId), MediaType.APPLICATION_JSON));
      } catch (IOException | IllegalStateException e) {
        set.remove(emitter);
      }
    }
    if (set.isEmpty()) {
      emitters.remove(recipientId, set);
    }
  }

  /** Live ticket count (test/observability seam, backlog #268). */
  int ticketCount() {
    return tickets.size();
  }

  /** Live emitter count for one user (test/observability seam). */
  int emitterCount(Long userId) {
    Set<SseEmitter> set = emitters.get(userId);
    return set == null ? 0 : set.size();
  }

  /**
   * Terminates every live stream of one user: the emitters are removed
   * from the registry and completed, so each client's EventSource
   * closes and reconnects through the refetch fallback. This is the
   * seam a session-revocation path would call; the stream test also
   * uses it to close a stream deterministically instead of waiting out
   * {@link #EMITTER_TIMEOUT_MS}. Returns how many streams were closed.
   */
  int completeStreamsForUser(Long userId) {
    Set<SseEmitter> set = emitters.remove(userId);
    if (set == null) {
      return 0;
    }
    int closed = 0;
    for (SseEmitter emitter : set) {
      emitter.complete();
      closed++;
    }
    return closed;
  }

  /**
   * Heartbeat: a comment frame every 25 s keeps proxies from idling the
   * connection out and doubles as a liveness sweep — emitters whose
   * send fails are dropped here even if no event ever targets them.
   */
  @Scheduled(fixedRate = 25_000)
  public void heartbeat() {
    emitters.forEach((userId, set) -> {
      for (SseEmitter emitter : set) {
        try {
          emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (IOException | IllegalStateException e) {
          set.remove(emitter);
        }
      }
      if (set.isEmpty()) {
        emitters.remove(userId, set);
      }
    });
  }
}
