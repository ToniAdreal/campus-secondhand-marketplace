package com.toni.marketplace.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption at rest for TOTP shared secrets (backlog #89). AES-256-GCM
 * from the JDK only ({@code javax.crypto}) — no new dependency, the same
 * approach {@link TotpService} takes for the TOTP math itself.
 *
 * <p>Storage format (versioned, so a future scheme can coexist):
 * {@code v1:<base64 12-byte IV>:<base64 ciphertext+GCM tag>}. A fresh
 * random IV is drawn per encryption, so encrypting the same secret twice
 * yields different stored strings. For the current 32-char Base32 secret
 * the stored form is 84 chars, which is why Flyway V19 widens
 * {@code totp_secret} from 64 to 255.
 *
 * <p>Legacy rows: secrets written before #89 are plain Base32. Base32's
 * alphabet (uppercase A–Z, 2–7) contains neither {@code v} nor {@code :},
 * so a stored value not starting with {@code v1:} is unambiguously a
 * legacy plaintext secret and {@link #decryptOrLegacy} returns it as-is;
 * {@link AuthService} re-encrypts it on the next setup/enable (and
 * opportunistically on a successful 2FA authenticate).
 *
 * <p>Fail-closed: a {@code v1:} value that does not parse, was tampered
 * with (the GCM tag check fails), or was encrypted under a different key
 * raises {@link DecryptionException}; callers translate it into the same
 * boring 400/401 as a wrong code — the exception never reaches the API
 * envelope.
 */
public class TotpSecretCipher {

  /** Version tag of the current storage format. */
  public static final String VERSION_PREFIX = "v1:";

  /** AES-256: the key must decode to exactly this many bytes. */
  private static final int KEY_BYTES = 32;

  /** 96-bit IV, the GCM recommendation; random per encryption. */
  private static final int IV_BYTES = 12;

  /** GCM authentication tag length in bits. */
  private static final int TAG_BITS = 128;

  private final SecretKey key;
  private final SecureRandom random = new SecureRandom();

  public TotpSecretCipher(String base64Key) {
    this.key = keyFromBase64(base64Key);
  }

  /**
   * Parses and validates the configured key. Anything other than Base64
   * decoding to exactly 32 bytes is a startup-time configuration error
   * (this runs while the cipher bean is constructed), never a per-request
   * failure.
   */
  static SecretKey keyFromBase64(String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      throw new IllegalStateException(
          "app.auth.totp.encryption-key is not set — set the APP_TOTP_ENCRYPTION_KEY "
              + "environment variable to a Base64-encoded 256-bit key, e.g. generated "
              + "with 'openssl rand -base64 32'.");
    }
    final byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(base64Key);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "app.auth.totp.encryption-key (APP_TOTP_ENCRYPTION_KEY) is not valid Base64.", e);
    }
    if (bytes.length != KEY_BYTES) {
      throw new IllegalStateException(
          "app.auth.totp.encryption-key (APP_TOTP_ENCRYPTION_KEY) must decode to exactly "
              + KEY_BYTES + " bytes (AES-256) but decoded to " + bytes.length + ".");
    }
    return new SecretKeySpec(bytes, "AES");
  }

  /** Encrypts a plaintext Base32 secret into the {@code v1:} storage form. */
  public String encrypt(String plainBase32Secret) {
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ciphertext =
          cipher.doFinal(plainBase32Secret.getBytes(StandardCharsets.US_ASCII));
      Base64.Encoder b64 = Base64.getEncoder();
      return VERSION_PREFIX + b64.encodeToString(iv) + ":" + b64.encodeToString(ciphertext);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES/GCM unavailable for TOTP secret encryption", e);
    }
  }

  /** True when the stored value already uses the {@code v1:} encrypted form. */
  public boolean isEncrypted(String stored) {
    return stored != null && stored.startsWith(VERSION_PREFIX);
  }

  /**
   * Resolves a stored value to the plaintext Base32 secret: decrypts a
   * {@code v1:} value, returns a legacy (pre-#89) plaintext value as-is.
   *
   * @throws DecryptionException when an encrypted value is malformed or
   *     fails the GCM integrity check (tampering or wrong key)
   */
  public String decryptOrLegacy(String stored) {
    if (stored == null || !isEncrypted(stored)) {
      return stored;
    }
    String[] parts = stored.substring(VERSION_PREFIX.length()).split(":", -1);
    if (parts.length != 2) {
      throw new DecryptionException("malformed encrypted TOTP secret");
    }
    try {
      byte[] iv = Base64.getDecoder().decode(parts[0]);
      byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.US_ASCII);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      // AEADBadTagException (tamper / wrong key) lands here, as do bad
      // Base64 and a wrong-length IV. All fail closed the same way.
      throw new DecryptionException("encrypted TOTP secret failed decryption", e);
    }
  }

  /** An encrypted stored secret could not be decrypted. Callers fail closed. */
  public static class DecryptionException extends RuntimeException {
    public DecryptionException(String message) {
      super(message);
    }

    public DecryptionException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
