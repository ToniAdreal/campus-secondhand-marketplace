package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Backlog #197 unit matrix for the breached-password screen behind the
 * shared {@link PasswordStrengthValidator}: the local denylist rejects
 * long-but-breached passwords that pass every other rule; only the
 * 5-hex-char prefix ever reaches a checker; a remote (fail-open)
 * checker outage is logged and lets the candidate through, while a
 * local (fail-closed) failure rejects; and neither the password nor its
 * full hash ever reaches a log line.
 */
class BreachedPasswordCheckerTest {

  private static final BreachedPasswordChecker LOCAL =
      new LocalDenylistBreachedPasswordChecker();

  /** A long, letters+digits password on the committed denylist. */
  private static final String BREACHED = "password1234";

  /** A long, letters+digits password in no denylist. */
  private static final String CLEAN = "Tr7ubadourXy9z";

  private Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attachLogCapture() {
    logger = (Logger) LoggerFactory.getLogger(PasswordStrengthValidator.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachLogCapture() {
    logger.detachAppender(appender);
  }

  @Test
  void denylistedLongPasswordIsRejectedByTheSharedCheck() {
    // Passes length (12) and composition (letters + digits) and is not
    // on the validator's short blocklist — the breach screen is the
    // only rule that can reject it.
    assertThat(PasswordStrengthValidator.check(BREACHED)).isEmpty();
    assertThat(PasswordStrengthValidator.check(BREACHED, 12, LOCAL))
        .hasValue(PasswordStrengthValidator.BREACHED_REASON);
  }

  @Test
  void everyDenylistSampleIsRejected() {
    for (String password : new String[]{"password1234", "qwerty123456",
        "iloveyou1234", "football1234", "welcome12345", "monkey123456"}) {
      assertThat(PasswordStrengthValidator.check(password, 12, LOCAL))
          .as("denylist entry %s", password)
          .hasValue(PasswordStrengthValidator.BREACHED_REASON);
    }
  }

  @Test
  void cleanLongPasswordPassesTheScreen() {
    assertThat(PasswordStrengthValidator.check(CLEAN, 12, LOCAL)).isEmpty();
  }

  @Test
  void localCheckerAnswersSuffixesForThePrefixOnlyShape() {
    String sha1 = PasswordStrengthValidator.sha1Hex(BREACHED);
    Set<String> suffixes = LOCAL.suffixesForPrefix(sha1.substring(0, 5));
    assertThat(suffixes).contains(sha1.substring(5));
    assertThat(LOCAL.failClosed()).isTrue();
  }

  @Test
  void onlyTheFiveCharPrefixEverReachesTheChecker() {
    List<String> seenPrefixes = new ArrayList<>();
    BreachedPasswordChecker capturing = new BreachedPasswordChecker() {
      @Override
      public Set<String> suffixesForPrefix(String prefix) {
        seenPrefixes.add(prefix);
        return Set.of();
      }

      @Override
      public boolean failClosed() {
        return false;
      }
    };
    assertThat(PasswordStrengthValidator.check(CLEAN, 12, capturing)).isEmpty();
    String sha1 = PasswordStrengthValidator.sha1Hex(CLEAN);
    assertThat(seenPrefixes).containsExactly(sha1.substring(0, 5));
    assertThat(seenPrefixes.get(0)).hasSize(5);
    // The full hash was never handed over in any argument.
    assertThat(seenPrefixes).noneMatch(s -> s.contains(sha1.substring(5)));
  }

  @Test
  void suffixMatchFromACheckerRejects() {
    String sha1 = PasswordStrengthValidator.sha1Hex(CLEAN);
    BreachedPasswordChecker matching = new BreachedPasswordChecker() {
      @Override
      public Set<String> suffixesForPrefix(String prefix) {
        return Set.of(sha1.substring(5));
      }

      @Override
      public boolean failClosed() {
        return false;
      }
    };
    assertThat(PasswordStrengthValidator.check(CLEAN, 12, matching))
        .hasValue(PasswordStrengthValidator.BREACHED_REASON);
  }

  @Test
  void remoteCheckerOutageFailsOpenAndLogsWithoutPasswordOrHash() {
    BreachedPasswordChecker down = new BreachedPasswordChecker() {
      @Override
      public Set<String> suffixesForPrefix(String prefix) {
        throw new IllegalStateException("simulated outage");
      }

      @Override
      public boolean failClosed() {
        return false;
      }
    };
    // Fail-open: the candidate is judged on the strength rules alone.
    assertThat(PasswordStrengthValidator.check(CLEAN, 12, down)).isEmpty();

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    String sha1 = PasswordStrengthValidator.sha1Hex(CLEAN);
    assertThat(event.getFormattedMessage()).doesNotContain(CLEAN);
    assertThat(event.getFormattedMessage()).doesNotContain(sha1);
    assertThat(event.getFormattedMessage()).doesNotContain(sha1.substring(5));
  }

  @Test
  void localCheckerFailureFailsClosed() {
    BreachedPasswordChecker brokenLocal = new BreachedPasswordChecker() {
      @Override
      public Set<String> suffixesForPrefix(String prefix) {
        throw new IllegalStateException("simulated local failure");
      }

      @Override
      public boolean failClosed() {
        return true;
      }
    };
    assertThat(PasswordStrengthValidator.check(CLEAN, 12, brokenLocal))
        .hasValue(PasswordStrengthValidator.BREACH_CHECK_FAILED_REASON);
    // A fail-closed rejection needs no fail-open log line.
    assertThat(appender.list).isEmpty();
  }

  @Test
  void nullCheckerSkipsTheScreen() {
    // Legacy/hand-built call sites without a checker keep the pre-#197
    // local-rules-only semantics exactly.
    assertThat(PasswordStrengthValidator.check(BREACHED, 12, null)).isEmpty();
  }

  @Test
  void nullAndBlankPasswordsNeverReachTheChecker() {
    // Backlog #269: the 3-arg check short-circuits on null/blank
    // (the local rules already return empty for those, and @NotBlank
    // owns them) — a mutant calling the checker anyway would hand a
    // null password to sha1Hex and throw.
    List<String> seen = new ArrayList<>();
    BreachedPasswordChecker capturing = new BreachedPasswordChecker() {
      @Override
      public Set<String> suffixesForPrefix(String prefix) {
        seen.add(prefix);
        return Set.of();
      }

      @Override
      public boolean failClosed() {
        return true;
      }
    };
    assertThat(PasswordStrengthValidator.check(null, 12, capturing)).isEmpty();
    assertThat(PasswordStrengthValidator.check("   ", 12, capturing)).isEmpty();
    assertThat(seen).isEmpty();
  }

  @Test
  void checkerReturningNullSuffixesPassesTheCandidate() {
    // Backlog #269: the `suffixes != null` guard had no test — a
    // mutant dropping it would NPE on a checker that answers null
    // instead of an empty set.
    BreachedPasswordChecker nullAnswering = new BreachedPasswordChecker() {
      @Override
      public Set<String> suffixesForPrefix(String prefix) {
        return null;
      }

      @Override
      public boolean failClosed() {
        return false;
      }
    };
    assertThat(PasswordStrengthValidator.check(CLEAN, 12, nullAnswering)).isEmpty();
  }

  @Test
  void requireStrongThreeArgOverloadAppliesTheBreachScreen() {
    // Backlog #269: the 3-arg requireStrong overload had no test at
    // all — a mutant delegating to the 2-arg overload would silently
    // drop the breach screen while every check() test stayed green.
    assertThatCode(() -> PasswordStrengthValidator.requireStrong(CLEAN, 12, LOCAL))
        .doesNotThrowAnyException();
    assertThatCode(() -> PasswordStrengthValidator.requireStrong(BREACHED, 12, null))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> PasswordStrengthValidator.requireStrong(BREACHED, 12, LOCAL))
        .isInstanceOf(com.toni.marketplace.common.WeakPasswordException.class)
        .hasMessage(PasswordStrengthValidator.BREACHED_REASON);
  }

  @Test
  void alreadyWeakPasswordsNeverReachTheChecker() {
    List<String> seen = new ArrayList<>();
    BreachedPasswordChecker capturing = new BreachedPasswordChecker() {
      @Override
      public Set<String> suffixesForPrefix(String prefix) {
        seen.add(prefix);
        return Set.of();
      }

      @Override
      public boolean failClosed() {
        return true;
      }
    };
    assertThat(PasswordStrengthValidator.check("abcdefghijkl", 12, capturing))
        .hasValue("password must contain both letters and digits");
    assertThat(seen).isEmpty();
  }
}
