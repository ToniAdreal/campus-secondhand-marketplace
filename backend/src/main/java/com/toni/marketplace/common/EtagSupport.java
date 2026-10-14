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
}
