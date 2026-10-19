package com.toni.marketplace.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Remote {@link BreachedPasswordChecker} (backlog #197) speaking the
 * HaveIBeenPwned range protocol: {@code GET {baseUrl}/{prefix}} answers
 * one {@code SUFFIX:count} line per breached hash under the prefix.
 * Only the 5-hex-char prefix ever leaves this process — the password
 * and its full hash never do (the suffix comparison is the caller's,
 * locally).
 *
 * <p>This bean is NEVER enabled silently: it replaces the offline
 * default only when the operator sets
 * {@code app.auth.breached-password.remote-enabled=true} (see
 * {@link BreachedPasswordProperties} and the bean wiring in
 * {@link AuthConfig}).
 *
 * <p>Fail-OPEN ({@link #failClosed()} is false): a corpus outage must
 * not lock registration and password changes deployment-wide, so any
 * transport failure or non-200 answer is thrown up to the validator,
 * which logs it (never the password, never the full hash) and lets the
 * candidate through on the strength rules alone.
 */
public class HttpBreachedPasswordChecker implements BreachedPasswordChecker {

  private final String baseUrl;
  private final Duration timeout;
  private final HttpClient client;

  public HttpBreachedPasswordChecker(String baseUrl, Duration timeout) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.timeout = timeout;
    this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  @Override
  public Set<String> suffixesForPrefix(String prefix) {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/" + prefix))
        .timeout(timeout)
        .header("Add-Padding", "true")
        .GET()
        .build();
    HttpResponse<String> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException("breached-password range lookup failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("breached-password range lookup interrupted", e);
    }
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "breached-password range lookup answered HTTP " + response.statusCode());
    }
    Set<String> suffixes = new LinkedHashSet<>();
    for (String line : response.body().split("\\R")) {
      String suffix = line.split(":", 2)[0].trim();
      if (!suffix.isEmpty()) {
        suffixes.add(suffix.toUpperCase(java.util.Locale.ROOT));
      }
    }
    return Set.copyOf(suffixes);
  }

  @Override
  public boolean failClosed() {
    return false;
  }
}
