package com.toni.marketplace.auth;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.toni.marketplace.common.InvalidCredentialsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * WebAuthn passkey ceremonies (backlog #220), the cryptographic half of
 * the feature. This service owns the challenge bytes, the
 * {@code ServerProperty} (rp-id + accepted origins, both from {@link
 * PasskeyProperties}), and the webauthn4j ceremony verification; {@link
 * AuthService} owns the HTTP-facing orchestration — the single-use
 * challenge rows, the credential rows, the audit/notification rows and
 * the login-202 gate — mirroring the {@code TotpService} /
 * {@code AuthService} split.
 *
 * <p>Verification uses webauthn4j's non-strict manager: attestation trust
 * anchors are not validated (the enrolment asks for {@code attestation:
 * "none"} and platform authenticators self-attest), but origin, rp-id,
 * challenge freshness, signature and user presence still are — the
 * checks that bind a ceremony to this server. A non-strict manager is
 * the documented webauthn4j posture for consumer deployments that
 * accept platform authenticators; the javadoc on the manager factory
 * says exactly that.
 *
 * <p>Every ceremony failure — malformed JSON, unknown challenge,
 * origin/rp-id mismatch, bad signature, counter regression — throws
 * {@link InvalidCredentialsException}: the identical 401, so the
 * response never reveals which check failed (the no-oracle discipline
 * of the TOTP exchange). Callers translate sequencing errors (no live
 * challenge row) to 400 themselves, before calling here.
 *
 * <p>Challenge bytes are 32 {@link SecureRandom} bytes; only their
 * SHA-256 hex is stored (see {@link PasskeyChallenge}), and the raw
 * bytes travel to the browser base64url-encoded inside the ceremony
 * options, exactly as the WebAuthn spec requires.
 */
@Service
public class PasskeyService {

  /** Challenge length in bytes (256 bits). */
  static final int CHALLENGE_BYTES = 32;

  /**
   * Credential types the server accepts at enrolment. ES256 (P-256) and
   * RS256 cover platform authenticators (Apple/Google) and roaming
   * security keys; the list is sent in the registration options'
   * {@code pubKeyCredParams}.
   */
  private static final List<PublicKeyCredentialParameters> PUB_KEY_CRED_PARAMS = List.of(
      new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY,
          COSEAlgorithmIdentifier.ES256),
      new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY,
          COSEAlgorithmIdentifier.RS256));

  private final PasskeyProperties props;
  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final SecureRandom random = new SecureRandom();
  private final WebAuthnManager webAuthnManager =
      WebAuthnManager.createNonStrictWebAuthnManager();
  private final ObjectConverter objectConverter = new ObjectConverter();

  public PasskeyService(PasskeyProperties props, Clock clock, ObjectMapper objectMapper) {
    this.props = props;
    this.clock = clock;
    this.objectMapper = objectMapper;
  }

  /** The configured Relying Party ID. */
  public String rpId() {
    return props.getRpId();
  }

  /** The credential parameters offered to the authenticator at enrolment. */
  public static List<PublicKeyCredentialParameters> pubKeyCredParams() {
    return PUB_KEY_CRED_PARAMS;
  }

  /** Fresh 256-bit challenge bytes. */
  public byte[] newChallengeBytes() {
    byte[] bytes = new byte[CHALLENGE_BYTES];
    random.nextBytes(bytes);
    return bytes;
  }

  /** Absolute expiry for a challenge issued now (the MutableClock drives this in tests). */
  public java.time.Instant challengeExpiresAt() {
    return clock.instant().plus(props.getChallengeTtl());
  }

  /** SHA-256 hex of the raw challenge bytes — the stored lookup key. */
  public static String challengeHash(byte[] challengeBytes) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(sha256.digest(challengeBytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** Unpadded base64url, the WebAuthn wire encoding for byte fields. */
  public static String base64UrlEncode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * Decodes unpadded (or padded) base64url. A malformed input throws
   * {@link IllegalArgumentException}; callers fold it into the
   * identical 401.
   */
  public static byte[] base64UrlDecode(String encoded) {
    return Base64.getUrlDecoder().decode(encoded);
  }

  /**
   * The server half of a ceremony: the configured rp-id, the accepted
   * origins, and the challenge the client must echo. An empty
   * {@code allowed-origins} fails closed — no origin ever matches.
   */
  public ServerProperty serverProperty(byte[] challengeBytes) {
    Set<Origin> origins = props.getAllowedOrigins().stream()
        .map(Origin::new)
        .collect(Collectors.toSet());
    return new ServerProperty(origins, props.getRpId(), new DefaultChallenge(challengeBytes));
  }

  /**
   * Verifies a registration ceremony response (the browser's
   * {@code PublicKeyCredential} JSON from {@code
   * navigator.credentials.create()}) against the issued challenge.
   *
   * @param credentialJson the raw JSON the browser produced
   * @param challengeBytes the raw challenge bytes issued with the options
   * @return the verified registration data — the caller persists the
   *     attested credential data (credential id, COSE public key) and
   *     the initial signature counter from the authenticator data
   * @throws InvalidCredentialsException on any verification failure —
   *     the identical 401, never a reason
   */
  public RegistrationData verifyRegistration(String credentialJson,
                                             byte[] challengeBytes) {
    RegistrationParameters params = new RegistrationParameters(
        serverProperty(challengeBytes), PUB_KEY_CRED_PARAMS,
        false, // userVerificationRequired: presence is enough for a second factor
        true); // userPresenceRequired
    final RegistrationData data;
    try {
      data = webAuthnManager.verifyRegistrationResponseJSON(credentialJson, params);
    } catch (RuntimeException e) {
      // Malformed JSON, challenge/origin/rp-id mismatch, bad attestation
      // signature — all fold into the identical 401 (no oracle). The
      // RuntimeException catch is deliberate: the converter throws its
      // own unchecked wrappers for hostile input, and none of them may
      // leak a reason or a 500.
      throw new InvalidCredentialsException();
    }
    return data;
  }

  /**
   * Reads the raw challenge bytes the client echoed inside the ceremony
   * response's {@code clientDataJSON} — the server looks its single-use
   * challenge row up by the SHA-256 of these bytes. A malformed
   * response is the identical 401.
   *
   * @param ceremonyJson the raw {@code PublicKeyCredential} JSON
   * @param registration true for a registration response, false for an assertion
   */
  public byte[] extractChallengeBytes(String ceremonyJson, boolean registration) {
    try {
      if (registration) {
        return webAuthnManager.parseRegistrationResponseJSON(ceremonyJson)
            .getCollectedClientData().getChallenge().getValue();
      }
      return webAuthnManager.parseAuthenticationResponseJSON(ceremonyJson)
          .getCollectedClientData().getChallenge().getValue();
    } catch (RuntimeException e) {
      throw new InvalidCredentialsException();
    }
  }

  /**
   * Verifies an assertion ceremony response (the browser's {@code
   * PublicKeyCredential} JSON from {@code navigator.credentials.get()})
   * against the issued challenge and the stored credential.
   *
   * @param assertionJson the raw JSON the browser produced
   * @param challengeBytes the raw challenge bytes issued with the options
   * @param credentialId the stored credential id (raw bytes)
   * @param publicKeyCose the stored COSE_Key public key (CBOR)
   * @param aaguid the stored authenticator AAGUID (16 bytes); webauthn4j
   *     refuses a null AAGUID when rebuilding the credential record, so
   *     a missing one fails closed as the identical 401, never a 500
   * @param storedSignCount the stored signature counter
   * @return the counter to persist afterwards: {@code max(stored,
   *     presented)} — a presented 0 (platform authenticators that never
   *     count) leaves the stored value untouched
   * @throws InvalidCredentialsException on any verification failure,
   *     including a regressed signature counter (possible cloned
   *     authenticator) — the identical 401, never a reason
   */
  public long verifyAssertion(String assertionJson, byte[] challengeBytes,
                              byte[] credentialId, byte[] publicKeyCose,
                              byte[] aaguid, long storedSignCount) {
    COSEKey coseKey;
    try {
      coseKey = parseCoseKey(publicKeyCose);
    } catch (RuntimeException e) {
      // Corrupt stored key material fails closed as the identical 401 —
      // never an exception leak, mirroring the undecryptable-TOTP-secret
      // discipline.
      throw new InvalidCredentialsException();
    }
    if (aaguid == null) {
      // A row that lost its AAGUID (pre-aaguid-column rows can never
      // exist — the column landed with the table — but a null must still
      // fail closed rather than 500 inside webauthn4j's non-null check).
      throw new InvalidCredentialsException();
    }
    AttestedCredentialData attestedCredentialData =
        new AttestedCredentialData(new AAGUID(aaguid), credentialId, coseKey);
    CredentialRecord credentialRecord = new CredentialRecordImpl(
        null, // attestation statement: not re-validated at assertion time
        null, null, null,
        storedSignCount,
        attestedCredentialData,
        null, null, null, null);
    AuthenticationParameters params = new AuthenticationParameters(
        serverProperty(challengeBytes), credentialRecord,
        List.of(credentialId),
        false, // userVerificationRequired
        true); // userPresenceRequired
    final AuthenticationData data;
    try {
      data = webAuthnManager.verifyAuthenticationResponseJSON(assertionJson, params);
    } catch (RuntimeException e) {
      // Malformed JSON, challenge/origin/rp-id mismatch, unknown
      // credential, bad signature — the identical 401 (no oracle).
      throw new InvalidCredentialsException();
    }
    long presented = data.getAuthenticatorData().getSignCount();
    checkCounterNotRegressed(storedSignCount, presented);
    return Math.max(storedSignCount, presented);
  }

  /**
   * The signature-counter clone-detection rule (backlog #220),
   * package-visible for direct unit tests. A non-zero counter that
   * fails to advance past the stored value means the credential was
   * very likely cloned onto a second authenticator — reject with the
   * identical 401. A presented 0 is exempt: platform authenticators
   * (Apple/Google passkeys) never implement the counter and always
   * report 0, so requiring advancement would lock out exactly the
   * authenticators this feature is for.
   */
  static void checkCounterNotRegressed(long storedSignCount, long presentedSignCount) {
    if (presentedSignCount != 0 && presentedSignCount <= storedSignCount) {
      throw new InvalidCredentialsException();
    }
  }

  /** Serializes a COSE public key to its CBOR form for storage. */
  public byte[] coseKeyBytes(COSEKey key) {
    return objectConverter.getCborConverter().writeValueAsBytes(key);
  }

  /**
   * Reads the top-level credential id from a ceremony response JSON
   * (the browser's {@code PublicKeyCredential.id}, base64url) — used
   * to select the stored credential BEFORE verification. The id itself
   * is never trusted: verification against the stored public key
   * decides. A missing or malformed id is the identical 401.
   */
  public String extractCredentialId(String ceremonyJson) {
    final JsonNode root;
    try {
      root = objectMapper.readTree(ceremonyJson);
    } catch (Exception e) {
      throw new InvalidCredentialsException();
    }
    String id = root.path("id").asText(null);
    if (id == null || id.isBlank()) {
      throw new InvalidCredentialsException();
    }
    return id;
  }

  /** Parses a stored CBOR COSE public key back. */
  public COSEKey parseCoseKey(byte[] bytes) {
    return objectConverter.getCborConverter().readValue(bytes, COSEKey.class);
  }
}
