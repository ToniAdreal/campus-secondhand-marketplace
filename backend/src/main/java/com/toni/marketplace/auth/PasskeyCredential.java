package com.toni.marketplace.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One enrolled WebAuthn passkey credential (backlog #220): the server's
 * half of the credential the authenticator created at enrolment. The
 * private key never leaves the authenticator — what is stored here is
 * the credential id (base64url, unique — it is also the
 * {@code allowCredentials} / {@code excludeCredentials} handle), the
 * public key in its COSE_Key CBOR form (re-serialized from the
 * attestation; parsed back for assertion verification), the
 * authenticator's AAGUID (webauthn4j refuses a null AAGUID when the
 * credential record is rebuilt for assertion verification), and the
 * signature counter for clone detection.
 *
 * <p>Passkeys are a second factor, not a password replacement: a row
 * here joins the 202-challenge family ({@code TOTP code / recovery code
 * / passkey assertion}), and passwordless login is explicitly out of
 * scope. Accounts are disabled, never deleted, so the user FK does not
 * cascade — {@link AuthService#closeAccount} deletes the rows explicitly
 * into the tombstone, mirroring the TOTP secret clearing.
 */
@Entity
@Table(name = "passkey_credential")
public class PasskeyCredential {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  /**
   * base64url credential id, as the browser reports it; unique. The
   * 1024-char column (not the WebAuthn-typical 86) is deliberate:
   * credential ids are authenticator-defined and can run long (the
   * webauthn4j test authenticator emits ~270 chars) — a 256 column
   * turned real enrolments into 500s (caught by PasskeyCeremonyTest).
   */
  @Column(name = "credential_id", nullable = false, unique = true, length = 1024)
  private String credentialId;

  /** The COSE_Key public key, CBOR-serialized. */
  @Column(name = "public_key_cose", nullable = false, columnDefinition = "BLOB")
  @JdbcTypeCode(SqlTypes.BLOB)
  private byte[] publicKeyCose;

  /**
   * The authenticator's AAGUID (16 bytes), as attested at enrolment.
   * webauthn4j's {@code AttestedCredentialData} refuses a null AAGUID at
   * assertion-verify time, so it is stored rather than reconstructed —
   * nullable only so a row can never 500 on a missing value (a null
   * here fails the assertion closed as the identical 401).
   */
  @Column(name = "aaguid", columnDefinition = "BINARY(16)")
  @JdbcTypeCode(SqlTypes.BINARY)
  private byte[] aaguid;

  /** Last verified signature counter; 0 when the authenticator does not count. */
  @Column(name = "sign_count", nullable = false)
  private long signCount;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected PasskeyCredential() {}

  public PasskeyCredential(Long userId, String credentialId, byte[] publicKeyCose,
                           long signCount, byte[] aaguid) {
    this.userId = userId;
    this.credentialId = credentialId;
    this.publicKeyCose = publicKeyCose;
    this.signCount = signCount;
    this.aaguid = aaguid;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getCredentialId() { return credentialId; }
  public byte[] getPublicKeyCose() { return publicKeyCose; }
  public byte[] getAaguid() { return aaguid; }
  public long getSignCount() { return signCount; }
  public void setSignCount(long signCount) { this.signCount = signCount; }
  public Instant getCreatedAt() { return createdAt; }
}
