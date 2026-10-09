package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Backlog #89 — AES-256-GCM encryption at rest for TOTP secrets. Pure unit
 * style: no Spring, fixed keys, the storage format and the fail-closed
 * behavior are pinned directly against {@link TotpSecretCipher}.
 */
class TotpSecretCipherTest {

  /** The RFC 6238 test secret, Base32 — a realistic plaintext shape. */
  private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

  private static String randomKeyBase64() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  private final TotpSecretCipher cipher = new TotpSecretCipher(randomKeyBase64());

  @Test
  void roundTripEncryptDecrypt() {
    String stored = cipher.encrypt(SECRET);

    assertThat(stored).startsWith(TotpSecretCipher.VERSION_PREFIX);
    assertThat(stored).doesNotContain(SECRET);
    // v1:<16-char b64 IV>:<64-char b64 ct> — 84 chars, the reason Flyway
    // V19 widened the column past 64.
    assertThat(stored).hasSize(84);
    assertThat(cipher.isEncrypted(stored)).isTrue();
    assertThat(cipher.decryptOrLegacy(stored)).isEqualTo(SECRET);
  }

  @Test
  void eachEncryptionUsesAFreshIv() {
    String first = cipher.encrypt(SECRET);
    String second = cipher.encrypt(SECRET);

    assertThat(first).isNotEqualTo(second);
    assertThat(cipher.decryptOrLegacy(first)).isEqualTo(SECRET);
    assertThat(cipher.decryptOrLegacy(second)).isEqualTo(SECRET);
  }

  @Test
  void legacyPlaintextPassesThroughUnchanged() {
    assertThat(cipher.isEncrypted(SECRET)).isFalse();
    assertThat(cipher.decryptOrLegacy(SECRET)).isEqualTo(SECRET);
    assertThat(cipher.decryptOrLegacy(null)).isNull();
  }

  @Test
  void tamperedCiphertextFailsClosed() {
    String stored = cipher.encrypt(SECRET);
    // Flip one character inside the ciphertext segment (past "v1:<iv>:").
    int pos = stored.lastIndexOf(':') + 3;
    char flipped = stored.charAt(pos) == 'A' ? 'B' : 'A';
    String tampered = stored.substring(0, pos) + flipped + stored.substring(pos + 1);

    Throwable thrown = catchThrowable(() -> cipher.decryptOrLegacy(tampered));

    assertThat(thrown).isInstanceOf(TotpSecretCipher.DecryptionException.class);
  }

  @Test
  void tamperedIvFailsClosed() {
    String stored = cipher.encrypt(SECRET);
    int pos = TotpSecretCipher.VERSION_PREFIX.length() + 2;
    char flipped = stored.charAt(pos) == 'A' ? 'B' : 'A';
    String tampered = stored.substring(0, pos) + flipped + stored.substring(pos + 1);

    Throwable thrown = catchThrowable(() -> cipher.decryptOrLegacy(tampered));

    assertThat(thrown).isInstanceOf(TotpSecretCipher.DecryptionException.class);
  }

  @Test
  void decryptionUnderADifferentKeyFailsClosed() {
    String stored = cipher.encrypt(SECRET);
    TotpSecretCipher other = new TotpSecretCipher(randomKeyBase64());

    Throwable thrown = catchThrowable(() -> other.decryptOrLegacy(stored));

    assertThat(thrown).isInstanceOf(TotpSecretCipher.DecryptionException.class);
  }

  @Test
  void malformedEncryptedValuesFailClosed() {
    assertThat(catchThrowable(() -> cipher.decryptOrLegacy("v1:not-base64!!!:AAAA")))
        .isInstanceOf(TotpSecretCipher.DecryptionException.class);
    assertThat(catchThrowable(() -> cipher.decryptOrLegacy("v1:onlyonesegment")))
        .isInstanceOf(TotpSecretCipher.DecryptionException.class);
    assertThat(catchThrowable(() -> cipher.decryptOrLegacy("v1::")))
        .isInstanceOf(TotpSecretCipher.DecryptionException.class);
  }

  @Test
  void keyMustBeBase64OfExactly32Bytes() {
    assertThat(catchThrowable(() -> new TotpSecretCipher(null)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(catchThrowable(() -> new TotpSecretCipher("")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(catchThrowable(() -> new TotpSecretCipher("not!base64")))
        .isInstanceOf(IllegalStateException.class);
    // 16 bytes is a valid AES key size in general, but this cipher is
    // AES-256 by contract — shorter keys are a configuration error.
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
    Throwable thrown = catchThrowable(() -> new TotpSecretCipher(shortKey));
    assertThat(thrown).isInstanceOf(IllegalStateException.class);
    assertThat(thrown.getMessage()).contains("32");
  }
}
