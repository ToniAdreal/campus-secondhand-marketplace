package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Backlog #78 — TOTP (RFC 6238, SHA-1, 6 digits, 30 s steps). The core
 * vectors come from RFC 6238 Appendix B (SHA-1, secret ASCII
 * "12345678901234567890"), truncated to 6 digits; the 081804 vector also
 * proves the leading zero survives formatting.
 *
 * <p>Pure unit style: no Spring, no mocks — time is a {@link MutableClock}.
 */
class TotpServiceTest {

  /** Base32 of the ASCII string "12345678901234567890" (the RFC test secret). */
  private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

  private TotpService serviceAt(Instant now) {
    return new TotpService(new MutableClock(now));
  }

  @Test
  void rfc6238Vectors() {
    assertThat(serviceAt(Instant.ofEpochSecond(59)).verify(RFC_SECRET, "287082")).isTrue();
    // Leading zero must survive: the 8-digit vector is 07081804.
    assertThat(serviceAt(Instant.ofEpochSecond(1111111109)).verify(RFC_SECRET, "081804")).isTrue();
    assertThat(serviceAt(Instant.ofEpochSecond(2000000000)).verify(RFC_SECRET, "279037")).isTrue();
  }

  @Test
  void base32RoundTripMatchesTheRfcSecret() {
    byte[] raw = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    assertThat(TotpService.base32Encode(raw)).isEqualTo(RFC_SECRET);
    assertThat(TotpService.base32Decode(RFC_SECRET)).isEqualTo(raw);
  }

  @Test
  void clockSkewOfOneStepIsAccepted() {
    // The t=59 code (T=1) is "287082" per the RFC vector above.
    String code = "287082";
    // One 30 s step in either direction verifies…
    assertThat(serviceAt(Instant.ofEpochSecond(30)).verify(RFC_SECRET, code)).isTrue();
    assertThat(serviceAt(Instant.ofEpochSecond(59)).verify(RFC_SECRET, code)).isTrue();
    assertThat(serviceAt(Instant.ofEpochSecond(89)).verify(RFC_SECRET, code)).isTrue();
    // t=0 is step 0 and the code is for step 1 — one step out, still covered.
    assertThat(serviceAt(Instant.ofEpochSecond(0)).verify(RFC_SECRET, code)).isTrue();
    // …two steps out does not: t=-30 is step -1 (window -2..0, code is step 1).
    assertThat(serviceAt(Instant.ofEpochSecond(-30)).verify(RFC_SECRET, code)).isFalse();
    assertThat(serviceAt(Instant.ofEpochSecond(119)).verify(RFC_SECRET, code)).isFalse();
  }

  @Test
  void wrongCodeIsRejected() {
    TotpService service = serviceAt(Instant.ofEpochSecond(59));
    assertThat(service.verify(RFC_SECRET, "000000")).isFalse();
    assertThat(service.verify(RFC_SECRET, "287083")).isFalse();
  }

  @Test
  void malformedCodesFailClosed() {
    TotpService service = serviceAt(Instant.ofEpochSecond(59));
    assertThat(service.verify(RFC_SECRET, null)).isFalse();
    assertThat(service.verify(RFC_SECRET, "")).isFalse();
    assertThat(service.verify(RFC_SECRET, "28708")).isFalse(); // 5 digits
    assertThat(service.verify(RFC_SECRET, "2870821")).isFalse(); // 7 digits
    assertThat(service.verify(RFC_SECRET, "abcdef")).isFalse();
    assertThat(service.verify(RFC_SECRET, " 87082")).isFalse();
  }

  @Test
  void corruptStoredSecretFailsClosedInsteadOfThrowing() {
    TotpService service = serviceAt(Instant.ofEpochSecond(59));
    assertThat(service.verify("NOT!BASE32!", "287082")).isFalse();
    assertThat(service.verify("", "287082")).isFalse();
  }

  @Test
  void generatedSecretsAreUniqueAndWellFormed() {
    TotpService service = serviceAt(Instant.ofEpochSecond(59));
    String first = service.generateSecret();
    String second = service.generateSecret();
    // 160 bits → 32 Base32 chars, no padding.
    assertThat(first).matches("[A-Z2-7]{32}");
    assertThat(second).matches("[A-Z2-7]{32}");
    assertThat(first).isNotEqualTo(second);
    // And the generated secret actually verifies: mint the code for this
    // instant and round-trip it through verify (covers Base32 decode of a
    // fresh secret + %06d formatting; the HOTP core is pinned by the RFC
    // vectors above).
    Instant at = Instant.ofEpochSecond(59);
    String code = service.codeAt(first, at);
    assertThat(service.verify(first, code)).isTrue();
  }

  @Test
  void otpauthUriHasTheExpectedShape() {
    TotpService service = serviceAt(Instant.ofEpochSecond(59));
    String uri = service.otpauthUri("CampusMarketplace", "alice", RFC_SECRET);
    assertThat(uri).startsWith("otpauth://totp/CampusMarketplace:alice?");
    assertThat(uri).contains("secret=" + RFC_SECRET);
    assertThat(uri).contains("issuer=CampusMarketplace");
    assertThat(uri).contains("digits=6&period=30");
  }

  // Backlog #285 — PIT widening to TotpService: the pure suite below pins
  // the branches the original eight tests never reached (recovery-code
  // formatting/normalization, label percent-encoding, lowercase Base32,
  // and the Base32 leftover-bits path), so the widened scope does not
  // ship known unkilled mutants with it. PIT itself cannot run in the
  // local sandbox (see docs/mutation.md), so these pins are the local
  // evidence; the kill is confirmed by the next CI backend-mutation run.

  @Test
  void base32ShortInputsPinTheLeftoverBits() {
    // Exact values cross-checked against java.util.Base64's Base32
    // (no padding): inputs whose bit length is not a multiple of 5
    // exercise the leftover-bits branch that the 20-byte RFC secret
    // (160 bits, exactly divisible by 5) never touches.
    assertThat(TotpService.base32Encode(new byte[] {0x00})).isEqualTo("AA");
    assertThat(TotpService.base32Encode(new byte[] {(byte) 0xff})).isEqualTo("74");
    assertThat(TotpService.base32Encode("f".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
        .isEqualTo("MY");
    assertThat(TotpService.base32Encode("fo".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
        .isEqualTo("MZXQ");
    assertThat(TotpService.base32Encode("foo".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
        .isEqualTo("MZXW6");
    assertThat(TotpService.base32Encode("foobar".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
        .isEqualTo("MZXW6YTBOI");
    // And each short encoding round-trips through decode.
    assertThat(TotpService.base32Decode("MZXW6"))
        .isEqualTo("foo".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    assertThat(TotpService.base32Decode("74")).isEqualTo(new byte[] {(byte) 0xff});
  }

  @Test
  void base32LowercaseDecodesToTheSameBytes() {
    byte[] raw = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    assertThat(TotpService.base32Decode(RFC_SECRET.toLowerCase())).isEqualTo(raw);
    // A lowercase secret verifies exactly like the uppercase one.
    assertThat(serviceAt(Instant.ofEpochSecond(59)).verify(RFC_SECRET.toLowerCase(), "287082"))
        .isTrue();
  }

  @Test
  void base32NonAsciiSecretFailsClosed() {
    // A character at or above the 128-entry decode table ('é' = 233)
    // must fail closed, not throw — the table-bounds branch, distinct
    // from the in-range-but-invalid '!' case pinned above.
    TotpService service = serviceAt(Instant.ofEpochSecond(59));
    assertThat(service.verify("GEZDGNBVGY3TQOJQé", "287082")).isFalse();
  }

  @Test
  void otpauthUriPercentEncodesSpecialAndNonAsciiLabels() {
    TotpService service = serviceAt(Instant.ofEpochSecond(59));
    String uri = service.otpauthUri("My Shop", "alice+test@example.com", RFC_SECRET);
    assertThat(uri).contains("My%20Shop:alice%2Btest%40example.com");
    assertThat(uri).contains("issuer=My%20Shop");
    // Unreserved characters pass through untouched.
    String plain = service.otpauthUri("a-b_c.d~e", "bob", RFC_SECRET);
    assertThat(plain).startsWith("otpauth://totp/a-b_c.d~e:bob?");
    // Non-ASCII labels encode as UTF-8 percent sequences (é = C3 A9).
    assertThat(service.otpauthUri("café", "alice", RFC_SECRET)).contains("caf%C3%A9");
  }

  @Test
  void recoveryCodeDisplayFormatAndNormalizeRoundTrip() {
    TotpService service = serviceAt(Instant.ofEpochSecond(59));
    String code = service.generateRecoveryCode();
    // 16 alphabet characters grouped 4-4-4-4 (19 chars incl. hyphens);
    // the alphabet excludes look-alikes 0/O, 1/I/L.
    assertThat(code).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4}"
        + "-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4}"
        + "-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4}"
        + "-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4}");
    assertThat(code.charAt(4)).isEqualTo('-');
    assertThat(code.charAt(9)).isEqualTo('-');
    assertThat(code.charAt(14)).isEqualTo('-');
    assertThat(TotpService.normalizeRecoveryCode(code)).isEqualTo(code.replace("-", ""));
    assertThat(TotpService.normalizeRecoveryCode(code)).hasSize(16);
    assertThat(service.generateRecoveryCode()).isNotEqualTo(code);
  }

  @Test
  void normalizeRecoveryCodeBranches() {
    // Note: L is deliberately absent from the recovery alphabet
    // (look-alike), so the valid fixture uses JKMN, never JKLM.
    assertThat(TotpService.normalizeRecoveryCode("ABCD-EFGH-JKMN-PQRS"))
        .isEqualTo("ABCDEFGHJKMNPQRS");
    // Lowercase uppercases, spaces and tabs strip like hyphens.
    assertThat(TotpService.normalizeRecoveryCode("abcd-efgh-jkmn-pqrs"))
        .isEqualTo("ABCDEFGHJKMNPQRS");
    assertThat(TotpService.normalizeRecoveryCode("abcd efgh\tjkmn pqrs"))
        .isEqualTo("ABCDEFGHJKMNPQRS");
    // Anything outside the alphabet fails, including look-alikes.
    assertThat(TotpService.normalizeRecoveryCode("ABCD-EFGH-JKMN-PQR0")).isNull();
    assertThat(TotpService.normalizeRecoveryCode("ABCD-EFGH-JKMN-PQR1")).isNull();
    assertThat(TotpService.normalizeRecoveryCode("ABCD-EFGH-JKMN-PQRO")).isNull();
    assertThat(TotpService.normalizeRecoveryCode("ABCD-EFGH-JKMN-PQRI")).isNull();
    assertThat(TotpService.normalizeRecoveryCode("ABCD-EFGH-JKMN-PQRL")).isNull();
    assertThat(TotpService.normalizeRecoveryCode("ABCD-EFGH-JKMN-PQR!")).isNull();
    // Wrong lengths fail even when every character is in the alphabet.
    assertThat(TotpService.normalizeRecoveryCode("ABCD-EFGH-JKMN-PQR")).isNull();
    assertThat(TotpService.normalizeRecoveryCode("ABCD-EFGH-JKMN-PQRST")).isNull();
    assertThat(TotpService.normalizeRecoveryCode("123456")).isNull();
    assertThat(TotpService.normalizeRecoveryCode("")).isNull();
    assertThat(TotpService.normalizeRecoveryCode(null)).isNull();
  }
}
