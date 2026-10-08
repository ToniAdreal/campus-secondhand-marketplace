package com.toni.marketplace.auth;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * TOTP (RFC 6238, SHA-1, 6 digits, 30 s steps) for two-factor login
 * (backlog #78). Pure JDK: HMAC-SHA1 from {@code javax.crypto}, Base32
 * (RFC 4648) implemented by hand so no new dependency is needed.
 *
 * <p>The shared secret is a 160-bit random value, stored Base32-encoded on
 * the {@code app_user} row (see V17 — encryption at rest is a declared
 * follow-up). Verification accepts the current 30 s window plus one step of
 * clock skew on either side; anything else fails closed.
 *
 * <p>Code comparisons use {@link MessageDigest#isEqual} (constant time) so a
 * wrong code leaks nothing about how close it was.
 */
@Service
public class TotpService {

  /** Digits in a TOTP code; authenticator apps universally use 6. */
  public static final int CODE_DIGITS = 6;

  /** TOTP time-step length in seconds. */
  public static final long TIME_STEP_SECONDS = 30;

  /** How many adjacent time steps are accepted around the current one. */
  private static final int SKEW_STEPS = 1;

  /** 160-bit secrets, the RFC 4226 recommendation. */
  private static final int SECRET_BYTES = 20;

  private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final int[] BASE32_DECODE = new int[128];

  static {
    for (int i = 0; i < BASE32_DECODE.length; i++) {
      BASE32_DECODE[i] = -1;
    }
    for (int i = 0; i < BASE32_ALPHABET.length(); i++) {
      BASE32_DECODE[BASE32_ALPHABET.charAt(i)] = i;
      BASE32_DECODE[Character.toLowerCase(BASE32_ALPHABET.charAt(i))] = i;
    }
  }

  private final SecureRandom random = new SecureRandom();
  private final Clock clock;

  public TotpService(Clock clock) {
    this.clock = clock;
  }

  /**
   * Generates a fresh 160-bit shared secret, Base32-encoded (32 chars, no
   * padding) for the {@code secret=} parameter of the otpauth URI and for
   * storage on the user row.
   */
  public String generateSecret() {
    byte[] bytes = new byte[SECRET_BYTES];
    random.nextBytes(bytes);
    return base32Encode(bytes);
  }

  /**
   * The provisioning URI the user scans into (or hand-types into) their
   * authenticator app. Label characters outside the RFC 3986 unreserved set
   * are percent-encoded.
   */
  public String otpauthUri(String issuer, String accountName, String base32Secret) {
    return "otpauth://totp/" + encodeLabel(issuer) + ":" + encodeLabel(accountName)
        + "?secret=" + base32Secret
        + "&issuer=" + encodeLabel(issuer)
        + "&algorithm=SHA1&digits=6&period=30";
  }

  /** Verifies a 6-digit code against the secret at the current instant. */
  public boolean verify(String base32Secret, String code) {
    return verify(base32Secret, code, clock.instant());
  }

  /**
   * The code for the step containing {@code now} (package-visible for
   * tests): lets a test mint a code for a freshly generated secret and
   * round-trip it through {@link #verify}. The HOTP core itself is covered
   * by the RFC 6238 vectors; this exercises Base32 decode + formatting +
   * the verify path end-to-end.
   */
  String codeAt(String base32Secret, Instant now) {
    byte[] secret = base32Decode(base32Secret);
    return hotp(secret, now.getEpochSecond() / TIME_STEP_SECONDS);
  }

  /**
   * Verifies a code against the secret at {@code now}; the package-visible
   * overload exists for deterministic tests.
   */
  boolean verify(String base32Secret, String code, Instant now) {
    if (code == null || code.length() != CODE_DIGITS || !isAllDigits(code)) {
      return false;
    }
    final byte[] secret;
    try {
      secret = base32Decode(base32Secret);
    } catch (IllegalArgumentException e) {
      // A corrupt stored secret fails closed rather than throwing.
      return false;
    }
    long step = now.getEpochSecond() / TIME_STEP_SECONDS;
    for (long s = step - SKEW_STEPS; s <= step + SKEW_STEPS; s++) {
      String expected = hotp(secret, s);
      if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
          code.getBytes(StandardCharsets.US_ASCII))) {
        return true;
      }
    }
    return false;
  }

  /** RFC 4226 HOTP: HMAC-SHA1(secret, 8-byte big-endian counter), truncated to 6 digits. */
  private static String hotp(byte[] secret, long counter) {
    byte[] message = new byte[8];
    for (int i = 7; i >= 0; i--) {
      message[i] = (byte) (counter & 0xff);
      counter >>= 8;
    }
    final byte[] hash;
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret, "HmacSHA1"));
      hash = mac.doFinal(message);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA1 unavailable", e);
    }
    int offset = hash[hash.length - 1] & 0x0f;
    int binary = ((hash[offset] & 0x7f) << 24)
        | ((hash[offset + 1] & 0xff) << 16)
        | ((hash[offset + 2] & 0xff) << 8)
        | (hash[offset + 3] & 0xff);
    int otp = binary % 1_000_000;
    // %06d keeps the leading zero — "081804" is a valid code, not "81804".
    return String.format("%06d", otp);
  }

  private static boolean isAllDigits(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  private static String encodeLabel(String label) {
    StringBuilder out = new StringBuilder(label.length());
    for (int i = 0; i < label.length(); i++) {
      char c = label.charAt(i);
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
          || c == '-' || c == '_' || c == '.' || c == '~') {
        out.append(c);
      } else {
        byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
          // Uppercase hex, per RFC 3986 §2.1 — the label itself keeps its case.
          out.append(String.format("%%%02X", b));
        }
      }
    }
    return out.toString();
  }

  static String base32Encode(byte[] bytes) {
    StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
    int buffer = 0;
    int bitsLeft = 0;
    for (byte b : bytes) {
      buffer = (buffer << 8) | (b & 0xff);
      bitsLeft += 8;
      while (bitsLeft >= 5) {
        out.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
        bitsLeft -= 5;
      }
    }
    if (bitsLeft > 0) {
      out.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
    }
    return out.toString();
  }

  static byte[] base32Decode(String encoded) {
    if (encoded == null || encoded.isEmpty()) {
      throw new IllegalArgumentException("empty Base32 secret");
    }
    int outLen = encoded.length() * 5 / 8;
    byte[] out = new byte[outLen];
    int buffer = 0;
    int bitsLeft = 0;
    int pos = 0;
    for (int i = 0; i < encoded.length(); i++) {
      char c = encoded.charAt(i);
      if (c >= BASE32_DECODE.length || BASE32_DECODE[c] < 0) {
        throw new IllegalArgumentException("invalid Base32 character: " + c);
      }
      buffer = (buffer << 5) | BASE32_DECODE[c];
      bitsLeft += 5;
      if (bitsLeft >= 8) {
        out[pos++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
        bitsLeft -= 8;
      }
    }
    return out;
  }
}
