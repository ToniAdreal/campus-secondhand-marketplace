package com.toni.marketplace.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * X-Request-ID correlation (backlog #44): a supplied id is echoed, a missing
 * or blank one is generated (UUID-shaped), and the id is visible in the MDC
 * only while the request is being handled.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RequestIdFilterTest {

  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  @Autowired
  private MockMvc mockMvc;

  private final RequestIdFilter filter = new RequestIdFilter();

  @Test
  void suppliedIdIsEchoed() throws Exception {
    mockMvc.perform(get("/actuator/health").header(RequestIdFilter.REQUEST_ID_HEADER, "order-42"))
        .andExpect(status().isOk())
        .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, "order-42"));
  }

  @Test
  void missingHeaderGeneratesUuidShapedId() throws Exception {
    String echoed = mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getHeader(RequestIdFilter.REQUEST_ID_HEADER);
    assertTrue(echoed != null && echoed.matches(UUID_PATTERN),
        "expected a generated UUID, got: " + echoed);
  }

  @Test
  void blankHeaderGeneratesUuidShapedId() throws Exception {
    String echoed = mockMvc.perform(get("/actuator/health")
            .header(RequestIdFilter.REQUEST_ID_HEADER, "   "))
        .andExpect(status().isOk())
        .andReturn().getResponse().getHeader(RequestIdFilter.REQUEST_ID_HEADER);
    assertTrue(echoed != null && echoed.matches(UUID_PATTERN),
        "expected a generated UUID, got: " + echoed);
  }

  @Test
  void suppliedIdIsEchoedEvenOnRejectedResponses() throws Exception {
    // The filter runs before auth, so a 401 still carries the echo header.
    mockMvc.perform(get("/actuator/metrics").header(RequestIdFilter.REQUEST_ID_HEADER, "req-7"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, "req-7"));
  }

  @Test
  void mdcCarriesTheIdDuringTheRequestAndIsClearedAfter() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "req-abc");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> seenInChain = new AtomicReference<>();

    filter.doFilterInternal(request, response,
        (req, res) -> seenInChain.set(MDC.get(RequestIdFilter.MDC_KEY)));

    assertEquals("req-abc", seenInChain.get(), "MDC must hold the id while the chain runs");
    assertEquals("req-abc", response.getHeader(RequestIdFilter.REQUEST_ID_HEADER));
    assertNull(MDC.get(RequestIdFilter.MDC_KEY),
        "MDC must be cleared after the request (pooled threads)");
  }

  @Test
  void mdcIsClearedEvenWhenDownstreamThrows() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    org.junit.jupiter.api.Assertions.assertThrows(ServletException.class, () ->
        filter.doFilterInternal(request, response,
            (req, res) -> { throw new ServletException("boom"); }));
    assertNull(MDC.get(RequestIdFilter.MDC_KEY), "MDC must be cleared on exception too");
  }

  @Test
  void hostileHeaderValuesAreSanitized() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    // CR/LF (log/header injection) plus an over-long id.
    request.addHeader(RequestIdFilter.REQUEST_ID_HEADER,
        "evil\r\nINJECTED: yes" + "x".repeat(200));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilterInternal(request, response, (req, res) -> { });

    String echoed = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
    assertTrue(echoed != null && !echoed.contains("\r") && !echoed.contains("\n"),
        "echoed id must not carry CR/LF, got: " + echoed);
    assertTrue(echoed.length() <= RequestIdFilter.MAX_REQUEST_ID_LENGTH,
        "echoed id must be capped, got length " + echoed.length());
    assertNull(MDC.get(RequestIdFilter.MDC_KEY));
  }
}
