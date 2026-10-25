package com.toni.marketplace.auth;

import com.toni.marketplace.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Frontend error telemetry intake (backlog #223): POST
 * /api/telemetry/errors, the receiving half of the SPA's error beacons
 * (see {@link TelemetryErrorService} for the parsing/sanitizing/counting
 * and its privacy rules).
 *
 * <p>Public in {@code SecurityConfig} by necessity: a render crash can
 * happen on an anonymous page, and the {@code window} error hooks fire
 * without any session — the beacon cannot carry a Bearer token. An
 * unauthenticated public write surface is therefore defended in depth
 * instead:
 *
 * <ul>
 *   <li>a hard body cap ({@code app.telemetry.errors.max-body-bytes},
 *       default 4 KB) enforced while reading the raw stream, before any
 *       parsing — over the cap answers {@code 413} in the
 *       {@code {code,message,data}} envelope;</li>
 *   <li>a dedicated per-IP token bucket in {@link AuthRateLimitFilter}
 *       (default 60/minute — a crash loop in one tab can otherwise flood
 *       the log/metric pipeline, see
 *       {@link AuthRateLimitProperties#getTelemetryErrorAttemptsPerMinute()}),
 *       answering {@code 429} in the same envelope with
 *       {@code Retry-After};</li>
 *   <li>only sanitized fields are ever logged or counted (the error name,
 *       the route template and the app version — never the message, the
 *       stack, or any URL query); nothing from the beacon is persisted
 *       or echoed back.</li>
 * </ul>
 *
 * <p>Accepted beacons answer {@code 204 No Content}, like the CSP intake
 * (#204): the sender is a fire-and-forget {@code fetch(keepalive)}
 * that never reads the body, so the acknowledgement carries none.
 * Errors the sender can act on — oversize (413), malformed (400) and
 * throttled (429, from the filter) — keep the envelope, like every other
 * error in the app.
 */
@RestController
@RequestMapping("/api/telemetry")
@io.swagger.v3.oas.annotations.tags.Tag(
    name = "telemetry", description = "Frontend error telemetry intake (public, beacon-sent)")
public class TelemetryErrorController {

  private final TelemetryErrorService errors;
  private final TelemetryErrorProperties props;

  public TelemetryErrorController(TelemetryErrorService errors, TelemetryErrorProperties props) {
    this.errors = errors;
    this.props = props;
  }

  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204",
          description = "beacon accepted (no content; beacons ignore the body)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
          description = "empty or malformed beacon body (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413",
          description = "beacon body over the configured cap (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
          description = "per-IP beacon rate limit (envelope, from AuthRateLimitFilter)")})
  @PostMapping("/errors")
  public ResponseEntity<ApiResponse<Void>> telemetryError(HttpServletRequest request)
      throws IOException {
    byte[] body = readCapped(request.getInputStream(), props.getMaxBodyBytes());
    if (body == null) {
      return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
          .body(ApiResponse.fail(HttpStatus.PAYLOAD_TOO_LARGE.value(),
              "telemetry error too large"));
    }
    if (body.length == 0) {
      return ResponseEntity.badRequest()
          .body(ApiResponse.fail(HttpStatus.BAD_REQUEST.value(), "empty telemetry error"));
    }
    try {
      errors.accept(body);
    } catch (TelemetryErrorService.MalformedTelemetryException e) {
      return ResponseEntity.badRequest()
          .body(ApiResponse.fail(HttpStatus.BAD_REQUEST.value(), "malformed telemetry error"));
    }
    return ResponseEntity.noContent().build();
  }

  /**
   * Reads at most {@code maxBytes} from the stream; returns {@code null}
   * as soon as the stream carries more than the cap, without draining
   * the rest — an oversize sender is answered, not accommodated.
   */
  private static byte[] readCapped(InputStream in, int maxBytes) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int total = 0;
    int read;
    while ((read = in.read(buffer)) != -1) {
      total += read;
      if (total > maxBytes) {
        return null;
      }
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }
}
