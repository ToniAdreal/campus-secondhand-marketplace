package com.toni.marketplace.auth;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default {@link BreachedPasswordChecker} (backlog #197): fully offline,
 * no network call, ever. It holds the SHA-1 hashes of a small committed
 * denylist of widely breached passwords that are long enough to pass
 * every other strength rule (12+ characters, letters and digits) — the
 * exact gap the validator's javadoc deferred before #197: a long but
 * widely-breached password such as {@code password1234} passed on
 * length and composition alone.
 *
 * <p>Honest scope: this is a FLOOR, not a corpus. Fifteen entries cannot
 * approximate the hundreds of millions of hashes in a real breach
 * corpus; they catch only the most predictable long passwords. A
 * deployment that wants the real corpus opts into
 * {@link HttpBreachedPasswordChecker} explicitly (see
 * {@link BreachedPasswordProperties}). Only hashes are committed —
 * never the plaintext passwords — and lookups are exact-hash matches,
 * so the check stays case-sensitive exactly as a corpus lookup is.
 *
 * <p>Fail-CLOSED ({@link #failClosed()} is true): the set is in-memory,
 * so there is no outage mode; if it somehow threw, the validator
 * rejects rather than silently skipping the screen.
 */
public class LocalDenylistBreachedPasswordChecker implements BreachedPasswordChecker {

  /** Full uppercase SHA-1 hex hashes of the denylisted passwords. */
  private static final Set<String> BREACHED_SHA1 = Set.of(
      "E6B6AFBD6D76BB5D2041542D7D2E3FAC5BB05593",
      "F3BA381B6BAEF526BF70FF220B1DA4906989224B",
      "2E38D47E05AAA48CE6B8A39DA5AC7FB6440813D4",
      "2AD8BE0D5458D76A178BC7F827980F6C491B7CFF",
      "5BEDF23C9E1C237629FEC3A543CC1A3EC67A251D",
      "3D3F799CFECF6C11BC90CB1F9FABB51EFE66FECE",
      "AD8740785A4A5FBF08EA28211F24920BE687A042",
      "A34A07FEA197C29103EBCB0D27BF525F09153050",
      "B5F50017653C165B94576300A4481EF80456AD35",
      "33C76F70AF66754CA47D19B17DA8DC232E125253",
      "3533DC31B5B114D597E3AA2D198BC0965D17905F",
      "36F37DCDBBB11F7303FD0D14DDB198B0245B3278",
      "67CC7F5060839414E2BEA6F63E98D86352FE65CC",
      "3672882E3540FA9F52B3429C8C2D151556320C2E",
      "4E373D2584208CEB1256B778B935C7288F6D4A54");

  @Override
  public Set<String> suffixesForPrefix(String prefix) {
    return BREACHED_SHA1.stream()
        .filter(hash -> hash.startsWith(prefix))
        .map(hash -> hash.substring(prefix.length()))
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public boolean failClosed() {
    return true;
  }
}
