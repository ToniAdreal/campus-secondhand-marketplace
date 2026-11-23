package com.toni.marketplace.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared conditional-GET helpers (extracted for backlog #166 from the
 * category list's #145 implementation, which remains the other caller).
 *
 * <p>{@link #matchesIfNoneMatch} implements the {@code If-None-Match}
 * comparison of RFC 9110 §13.1.2: the header may be a comma-separated
 * list, entries may be weak ({@code W/} prefix — stripped, because this
 * header compares weakly) or quoted, and {@code *} matches any current
 * representation.
 */
public final class EtagSupport {

  private EtagSupport() {}

  /** Hex SHA-256 over a canonical string form — the strong-validator body. */
  public static String sha256Hex(String canonical) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      byte[] digest = sha256.digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed on every Java platform; this never happens.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** Whether an {@code If-None-Match} header value matches the current validator. */
  public static boolean matchesIfNoneMatch(String ifNoneMatch, String etag) {
    if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
      return false;
    }
    for (String candidate : ifNoneMatch.split(",")) {
      String value = candidate.trim();
      if (value.equals("*")) {
        return true;
      }
      if (value.startsWith("W/")) {
        value = value.substring(2).trim();
      }
      if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
        value = value.substring(1, value.length() - 1);
      }
      if (value.equals(etag)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether an {@code If-Match} header value matches the current validator
   * (backlog #425, RFC 9110 §13.1.1 strong comparison): the header may be
   * a comma-separated list or {@code *} (which matches any current
   * representation — the caller only evaluates it for a row that exists);
   * entries are compared STRONGLY, so a weak ({@code W/}) entry never
   * matches, unlike {@link #matchesIfNoneMatch}. A blank header matches
   * nothing (a missing header is the caller's unconditional case, never
   * this method).
   */
  public static boolean matchesIfMatch(String ifMatch, String etag) {
    if (ifMatch == null || ifMatch.isBlank()) {
      return false;
    }
    for (String candidate : ifMatch.split(",")) {
      String value = candidate.trim();
      if (value.equals("*")) {
        return true;
      }
      if (value.startsWith("W/")) {
        continue;
      }
      if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
        value = value.substring(1, value.length() - 1);
      }
      if (value.equals(etag)) {
        return true;
      }
    }
    return false;
  }
}
