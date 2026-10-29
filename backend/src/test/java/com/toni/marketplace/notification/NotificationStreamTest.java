package com.toni.marketplace.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.MutableClock;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SSE push for notifications (backlog #203): the stream is opened with
 * a one-time ticket (never the access token in a URL), events carry
 * exactly {@code {type, id}} — no body data — and a recorded
 * notification reaches a live stream after its transaction commits.
 * The ticket-less / reused / expired cases are all the same 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationStreamTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private NotificationService notificationService;

  @Autowired
  private NotificationStreamService streamService;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @AfterEach
  void cleanUp() {
    notifications.deleteAll();
    users.deleteAll();
  }

  private User newUser() {
    String tag = "stream-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String issueTicket(User user) throws Exception {
    String body = mockMvc.perform(post("/api/notifications/stream-ticket")
            .header("Authorization", "Bearer " + jwt.createTokenPair(user).accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.data.ticket");
  }

  @Test
  void streamTicketRequiresAuthentication() throws Exception {
    mockMvc.perform(post("/api/notifications/stream-ticket"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void streamWithoutOrWithGarbageTicketIs401() throws Exception {
    mockMvc.perform(get("/api/notifications/stream"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mockMvc.perform(get("/api/notifications/stream").param("ticket", "not-a-ticket"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void ticketIsSingleUse() throws Exception {
    User user = newUser();
    String ticket = issueTicket(user);

    mockMvc.perform(get("/api/notifications/stream").param("ticket", ticket))
        .andExpect(request().asyncStarted());

    // The same ticket a second time is indistinguishable from garbage.
    mockMvc.perform(get("/api/notifications/stream").param("ticket", ticket))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void recordedNotificationIsPushedToTheRecipientsStream() throws Exception {
    User seller = newUser();
    String ticket = issueTicket(seller);
    MvcResult stream = mockMvc.perform(get("/api/notifications/stream").param("ticket", ticket))
        .andExpect(request().asyncStarted())
        .andReturn();

    // Direct service call: no ambient transaction, so the after-commit
    // publish fires immediately; the row id is the one just saved.
    notificationService.record(seller.getId(), NotificationType.ORDER_PAID, 4242L);
    Long rowId = notifications.findAll().get(0).getId();

    // Close the stream before dispatching: asyncDispatch waits for the
    // emitter to complete, and a live stream only completes on its
    // 30-minute timeout — without this the test would hang that long.
    assertThat(streamService.completeStreamsForUser(seller.getId())).isEqualTo(1);

    String body = mockMvc.perform(asyncDispatch(stream))
        .andReturn().getResponse().getContentAsString();
    String dataLine = body.lines()
        .filter(line -> line.startsWith("data:"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no data frame in stream body: " + body));
    assertThat(body).contains("event:notification");
    Map<String, Object> payload = new ObjectMapper()
        .readValue(dataLine.substring("data:".length()).trim(), new TypeReference<>() {});
    // The payload key set is exactly {type, id} — no body data, ever.
    assertThat(payload.keySet()).containsExactlyInAnyOrder("type", "id");
    assertThat(payload.get("type")).isEqualTo("ORDER_PAID");
    assertThat(((Number) payload.get("id")).longValue()).isEqualTo(rowId);
  }

  @Test
  void eventPayloadCarriesExactlyTypeAndId() {
    Map<String, Object> payload =
        NotificationStreamService.eventPayload(NotificationType.OFFER_ACCEPTED, 7L);
    assertThat(payload.keySet()).containsExactlyInAnyOrder("type", "id");
    assertThat(payload.get("type")).isEqualTo("OFFER_ACCEPTED");
    assertThat(payload.get("id")).isEqualTo(7L);
  }

  @Test
  void ticketsExpireAndAreConsumedExactlyOnce() {
    MutableClock clock = new MutableClock();
    NotificationStreamService service = new NotificationStreamService(clock);

    String ticket = service.issueTicket(99L);
    assertThat(service.consumeTicket(ticket)).isEqualTo(99L);
    assertThat(service.consumeTicket(ticket)).isNull();

    String expiring = service.issueTicket(100L);
    clock.advance(Duration.ofSeconds(61));
    assertThat(service.consumeTicket(expiring)).isNull();
    assertThat(service.consumeTicket(null)).isNull();
    assertThat(service.consumeTicket("unknown")).isNull();
  }

  @Test
  void consumeTicketPurgesExpiredTicketsIncludingUnpresentedOnes() {
    MutableClock clock = new MutableClock();
    NotificationStreamService service = new NotificationStreamService(clock);

    // Two quiet accounts: neither ticket is ever presented again, so
    // without a consume-path purge both would linger past their TTL.
    service.issueTicket(100L);
    service.issueTicket(101L);
    assertThat(service.ticketCount()).isEqualTo(2);

    clock.advance(Duration.ofSeconds(61));
    // Presenting an unknown ticket still sweeps the expired ones.
    assertThat(service.consumeTicket("unknown")).isNull();
    assertThat(service.ticketCount()).isZero();

    // A valid ticket still consumes exactly once, and a second use 401s.
    String valid = service.issueTicket(102L);
    assertThat(service.consumeTicket(valid)).isEqualTo(102L);
    assertThat(service.consumeTicket(valid)).isNull();
    assertThat(service.ticketCount()).isZero();

    // An expired ticket 401s and is gone from the map.
    String expiring = service.issueTicket(103L);
    clock.advance(Duration.ofSeconds(61));
    assertThat(service.consumeTicket(expiring)).isNull();
    assertThat(service.ticketCount()).isZero();
  }

  @Test
  void completeStreamsForUserClosesOnlyThatUsersStreams() {
    NotificationStreamService service = new NotificationStreamService(new MutableClock());
    service.openStream(5L);
    service.openStream(5L);
    service.openStream(6L);
    assertThat(service.completeStreamsForUser(5L)).isEqualTo(2);
    assertThat(service.emitterCount(5L)).isZero();
    assertThat(service.emitterCount(6L)).isEqualTo(1);
    assertThat(service.completeStreamsForUser(5L)).isZero();
  }

  @Test
  void emitterRegistryCleansUpOnCompletion() {
    NotificationStreamService service = new NotificationStreamService(new MutableClock());
    var emitter = service.openStream(5L);
    assertThat(service.emitterCount(5L)).isEqualTo(1);
    emitter.complete();
    assertThat(service.emitterCount(5L)).isEqualTo(0);
    // Publishing to a user with no live stream is a silent no-op.
    service.publish(5L, NotificationType.ORDER_PAID, 1L);
    assertThat(service.emitterCount(5L)).isEqualTo(0);
  }
}
