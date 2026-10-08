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
}
