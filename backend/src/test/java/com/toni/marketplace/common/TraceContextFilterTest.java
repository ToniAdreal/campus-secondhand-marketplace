package com.toni.marketplace.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * W3C trace-context correlation (backlog #214): a valid inbound
 * {@code traceparent} contributes its trace-id (this server mints its own
 * span-id and preserves the flags), an absent header gets a freshly minted
 * context, and a malformed header is ignored wholesale — never echoed —
 * with a fresh context minted instead. The trace-id/span-id ride the MDC
 * for the duration of the request so every log line carries them, and the
 * response echoes a well-formed {@code traceparent}. Honest scope: this
 * is correlation only, no exporter and no distributed tracing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TraceContextFilterTest {

  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String PARENT_ID = "00f067aa0ba902b7";
  private static final String VALID = "00-" + TRACE_ID + "-" + PARENT_ID + "-01";
  private static final String TRACEPARENT_PATTERN =
      "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}";

  @Autowired
  private MockMvc mockMvc;

  private final RequestIdFilter filter = new RequestIdFilter();

  private record Run(MockHttpServletResponse response, String traceIdInChain,
                     String spanIdInChain) {}

  private Run runWith(String traceparent) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (traceparent != null) {
      request.addHeader(RequestIdFilter.TRACEPARENT_HEADER, traceparent);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> traceSeen = new AtomicReference<>();
    AtomicReference<String> spanSeen = new AtomicReference<>();
    filter.doFilterInternal(request, response, (req, res) -> {
      traceSeen.set(MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY));
      spanSeen.set(MDC.get(RequestIdFilter.SPAN_ID_MDC_KEY));
    });
    return new Run(response, traceSeen.get(), spanSeen.get());
  }

  private static String[] parts(MockHttpServletResponse response) {
    String echoed = response.getHeader(RequestIdFilter.TRACEPARENT_HEADER);
    assertThat(echoed).as("response must always carry a traceparent").isNotNull();
    assertThat(echoed).matches(TRACEPARENT_PATTERN);
    return echoed.split("-");
  }

  @Test
  void validInboundTraceIdIsPropagatedWithAFreshSpanAndPreservedFlags() throws Exception {
    Run run = runWith(VALID);
    String[] echoed = parts(run.response());
    assertEquals(TRACE_ID, echoed[1], "the inbound trace-id must be propagated");
    assertThat(echoed[2]).isNotEqualTo(PARENT_ID)
        .as("our span-id is minted fresh, never the inbound parent-id");
    assertEquals("01", echoed[3], "inbound trace-flags are preserved");
    assertEquals(TRACE_ID, run.traceIdInChain());
    assertEquals(echoed[2], run.spanIdInChain(), "MDC span-id must match the echoed span-id");
    assertNull(MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY), "MDC cleared after the request");
    assertNull(MDC.get(RequestIdFilter.SPAN_ID_MDC_KEY), "MDC cleared after the request");
  }

  @Test
  void unsampledInboundFlagsArePreservedToo() throws Exception {
    Run run = runWith("00-" + TRACE_ID + "-" + PARENT_ID + "-00");
    assertEquals("00", parts(run.response())[3]);
  }

  @Test
  void futureVersionWithExtraFieldsIsAcceptedPerSpec() throws Exception {
    // A non-00 version may append fields; the parser validates the first
    // four and ignores the rest, but our response is always version 00.
    Run run = runWith("01-" + TRACE_ID + "-" + PARENT_ID + "-01-extrafield");
    String[] echoed = parts(run.response());
    assertEquals("00", echoed[0]);
    assertEquals(TRACE_ID, echoed[1]);
    assertEquals("01", echoed[3]);
  }

  @Test
  void absentHeaderMintsAFreshSampledContext() throws Exception {
    Run run = runWith(null);
    String[] echoed = parts(run.response());
    assertEquals("01", echoed[3], "a minted context is marked sampled");
    assertEquals(echoed[1], run.traceIdInChain());
    assertEquals(echoed[2], run.spanIdInChain());
  }

  static Stream<String> malformedValues() {
    return Stream.of(
        "garbage",
        "00-" + TRACE_ID + "-" + PARENT_ID,                    // missing flags
        "00-" + TRACE_ID + "-" + PARENT_ID + "-01-extra",      // v00: extra field
        "ff-" + TRACE_ID + "-" + PARENT_ID + "-01",            // forbidden version
        "0-" + TRACE_ID + "-" + PARENT_ID + "-01",             // short version
        "00-00000000000000000000000000000000-" + PARENT_ID + "-01", // zero trace-id
        "00-" + TRACE_ID + "-0000000000000000-01",             // zero parent-id
        "00-" + TRACE_ID.toUpperCase() + "-" + PARENT_ID + "-01",   // uppercase hex
        "00-" + TRACE_ID.substring(1) + "-" + PARENT_ID + "-01",    // short trace-id
        "00-" + TRACE_ID + "-" + PARENT_ID + "-1",             // short flags
        "00-" + TRACE_ID + "-" + PARENT_ID + "-zz",            // non-hex flags
        "00_" + TRACE_ID + "_" + PARENT_ID + "_01",            // wrong separator
        "");
  }

  @ParameterizedTest
  @MethodSource("malformedValues")
  void malformedInboundIsIgnoredNeverEchoedAndFreshlyMinted(String malformed) throws Exception {
    Run run = runWith(malformed);
    String echoedHeader = run.response().getHeader(RequestIdFilter.TRACEPARENT_HEADER);
    assertThat(echoedHeader).isNotEqualTo(malformed).matches(TRACEPARENT_PATTERN);
    String[] echoed = parts(run.response());
    if (!malformed.contains(TRACE_ID) || malformed.equals(VALID)) {
      // nothing to pin beyond well-formedness
    } else {
      assertThat(echoed[1])
          .as("a malformed trace-id must never leak into the response")
          .isNotEqualTo(TRACE_ID);
    }
    assertEquals("01", echoed[3], "a replacement context is minted sampled");
  }

  @Test
  void mdcIsClearedEvenWhenDownstreamThrows() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(RequestIdFilter.TRACEPARENT_HEADER, VALID);
    MockHttpServletResponse response = new MockHttpServletResponse();
    org.junit.jupiter.api.Assertions.assertThrows(ServletException.class, () ->
        filter.doFilterInternal(request, response,
            (req, res) -> { throw new ServletException("boom"); }));
    assertNull(MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY));
    assertNull(MDC.get(RequestIdFilter.SPAN_ID_MDC_KEY));
  }

  @Test
  void logEventsEmittedDuringTheRequestCarryTheTraceId() throws Exception {
    Logger probe = (Logger) LoggerFactory.getLogger("com.toni.marketplace.common.TraceProbe");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    probe.addAppender(appender);
    try {
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.addHeader(RequestIdFilter.TRACEPARENT_HEADER, VALID);
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilterInternal(request, response,
          (req, res) -> probe.info("trace probe message"));
      assertThat(appender.list).hasSize(1);
      ILoggingEvent event = appender.list.get(0);
      assertEquals(TRACE_ID, event.getMDCPropertyMap().get(RequestIdFilter.TRACE_ID_MDC_KEY),
          "a log line emitted while handling the request must carry its trace id");
      assertEquals(parts(response)[2],
          event.getMDCPropertyMap().get(RequestIdFilter.SPAN_ID_MDC_KEY));
    } finally {
      probe.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void plainLogPatternRendersTraceAndSpanIds() throws Exception {
    String logback = Files.readString(
        Path.of(new ClassPathResource("logback-spring.xml").getURI()), StandardCharsets.UTF_8);
    assertThat(logback)
        .as("the default console pattern must render the trace MDC values on every line")
        .contains("%X{traceId}").contains("%X{spanId}");
  }

  @Test
  void mockMvcValidTraceparentPropagatesAndMalformedIsReplaced() throws Exception {
    String echoed = mockMvc.perform(get("/actuator/health")
            .header(RequestIdFilter.TRACEPARENT_HEADER, VALID))
        .andExpect(status().isOk())
        .andReturn().getResponse().getHeader(RequestIdFilter.TRACEPARENT_HEADER);
    assertThat(echoed).matches(TRACEPARENT_PATTERN);
    assertEquals(TRACE_ID, echoed.split("-")[1]);

    String replaced = mockMvc.perform(get("/actuator/health")
            .header(RequestIdFilter.TRACEPARENT_HEADER, "not-a-traceparent"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getHeader(RequestIdFilter.TRACEPARENT_HEADER);
    assertThat(replaced).matches(TRACEPARENT_PATTERN).isNotEqualTo("not-a-traceparent");

    String minted = mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getHeader(RequestIdFilter.TRACEPARENT_HEADER);
    assertThat(minted).matches(TRACEPARENT_PATTERN);
  }
}
