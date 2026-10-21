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
 * CSP violation report intake (backlog #204): POST
 * /api/security/csp-report, the receiving half of the opt-in
 * Content-Security-Policy from backlog #80 (see {@link CspReportService}
 * for the parsing/sanitizing/counting and its privacy rules).
 *
 * <p>Public in {@code SecurityConfig} by necessity: browsers send CSP
 * reports automatically, without any user session, and the
 * {@code report-uri} / Reporting API machinery cannot attach a Bearer
 * token. An unauthenticated public write surface is therefore defended
 * in depth instead:
 *
 * <ul>
 *   <li>a hard body cap ({@code app.security.csp-report.max-body-bytes},
 *       default 16 KB) enforced while reading the raw stream, before
 *       any parsing — over the cap answers {@code 413} in the
 *       {@code {code,message,data}} envelope;</li>
 *   <li>a dedicated per-IP token bucket in {@link AuthRateLimitFilter}
 *       (default 60/minute — browser-generated bursts, see
 *       {@link AuthRateLimitProperties#getCspReportAttemptsPerMinute()}),
 *       answering {@code 429} in the same envelope with
 *       {@code Retry-After};</li>
 *   <li>only sanitized fields are ever logged or counted (the service
 *       drops the blocked URI's path/query and the whole
 *       {@code document-uri}); nothing from the report is persisted or
 *       echoed back.</li>
 * </ul>
 *
 * <p>Accepted reports answer {@code 204 No Content}, not the usual
 * envelope: browsers ignore the response body of a report POST
 * entirely, and 204 is the idiomatic acknowledgement for a fire-and-
 * forget browser report (the webhook receiver's 200-envelope rule does
 * not apply — a PSP sender does read its acks; a browser never reads
 * this one). Errors the sender can act on — oversize (413), malformed
 * (400) and throttled (429, from the filter) — keep the envelope, like
 * every other error in the app. The endpoint accepts any content type:
 * browsers variously send {@code application/csp-report},
 * {@code application/reports+json} and {@code text/plain}.
 *
 * <p>The route is advertised to browsers only when both
 * {@code app.security.headers.csp-enabled} and
 * {@code app.security.headers.csp-report-enabled} are on (see
 * {@code SecurityConfig}): the policy then gains a
 * {@code report-uri /api/security/csp-report} directive and responses
 * carry a {@code Reporting-Endpoints} header. The intake itself stays
 * mounted regardless, so a deployment that mirrors the CSP at its
 * proxy (the compose nginx case documented in {@code SecurityConfig})
 * can still point its own {@code report-uri} here.
 */
@RestController
@RequestMapping("/api/security")
@io.swagger.v3.oas.annotations.tags.Tag(
    name = "security", description = "CSP violation report intake (public, browser-sent)")
public class CspReportController {

  private final CspReportService reports;
  private final CspReportProperties props;

  public CspReportController(CspReportService reports, CspReportProperties props) {
    this.reports = reports;
    this.props = props;
  }

  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204",
          description = "report accepted (no content; browsers ignore the body)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
          description = "empty or malformed report body (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413",
          description = "report body over the configured cap (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
          description = "per-IP report rate limit (envelope, from AuthRateLimitFilter)")})
  @PostMapping("/csp-report")
  public ResponseEntity<ApiResponse<Void>> cspReport(HttpServletRequest request)
      throws IOException {
    byte[] body = readCapped(request.getInputStream(), props.getMaxBodyBytes());
    if (body == null) {
      return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
          .body(ApiResponse.fail(HttpStatus.PAYLOAD_TOO_LARGE.value(),
              "csp report too large"));
    }
    if (body.length == 0) {
      return ResponseEntity.badRequest()
          .body(ApiResponse.fail(HttpStatus.BAD_REQUEST.value(), "empty csp report"));
    }
    try {
      reports.accept(body);
    } catch (CspReportService.MalformedCspReportException e) {
      return ResponseEntity.badRequest()
          .body(ApiResponse.fail(HttpStatus.BAD_REQUEST.value(), "malformed csp report"));
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
