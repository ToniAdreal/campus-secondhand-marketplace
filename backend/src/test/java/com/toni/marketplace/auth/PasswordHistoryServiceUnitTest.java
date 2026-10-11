package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toni.marketplace.common.PasswordReuseException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure unit tests (no Spring context) for the shared password-history
 * check (backlog #139). The encoder seam is stubbed with a transparent
 * fake — {@code matches(raw, "H:" + raw)} — so each case states exactly
 * which stored hash the candidate is compared against; the real BCrypt
 * behaviour is covered end-to-end by {@code PasswordHistoryTest}.
 */
@ExtendWith(MockitoExtension.class)
class PasswordHistoryServiceUnitTest {

  @Mock
  private PasswordHistoryRepository history;

  @Mock
  private PasswordService passwords;

  private PasswordHistoryService service;

  private User alice;

  @BeforeEach
  void setUp() {
    service = new PasswordHistoryService(history, passwords, new PasswordPolicyProperties());
    alice = new User("alice", "alice@example.com", "H:current-pass-1");
    ReflectionTestUtils.setField(alice, "id", 7L);
    // Lenient: recordOutgoingStoresTheUsersCurrentHash exercises the
    // write path only and never consults the encoder seam.
    lenient().when(passwords.matches(anyString(), anyString()))
        .thenAnswer(inv -> inv.getArgument(1).equals("H:" + inv.getArgument(0)));
  }

  @Test
  void reusingTheCurrentPasswordIsRejectedWithTheCurrentMessage() {
    assertThatThrownBy(() -> service.requireNotReused(alice, "current-pass-1"))
        .isInstanceOf(PasswordReuseException.class)
        .hasMessage("new password must differ from the current password");
  }

  @Test
  void reusingARecentPasswordIsRejectedWithTheRecentMessage() {
    when(history.findByUserIdOrderByIdDesc(eq(7L), any(Pageable.class)))
        .thenReturn(List.of(new PasswordHistory(7L, "H:older-pass-1")));

    assertThatThrownBy(() -> service.requireNotReused(alice, "older-pass-1"))
        .isInstanceOf(PasswordReuseException.class)
        .hasMessage("new password must differ from a recent password");
  }

  @Test
  void theHistoryWindowIsExactlyTheConfiguredSize() {
    when(history.findByUserIdOrderByIdDesc(eq(7L), any(Pageable.class)))
        .thenReturn(List.of());

    assertThatCode(() -> service.requireNotReused(alice, "brand-new-pass"))
        .doesNotThrowAnyException();

    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    verify(history).findByUserIdOrderByIdDesc(eq(7L), page.capture());
    assertThat(page.getValue().getPageSize()).isEqualTo(5);
    assertThat(page.getValue().getPageNumber()).isEqualTo(0);
  }

  @Test
  void aPasswordOutsideTheWindowIsAllowed() {
    // The repository returns only the newest 5 rows; a candidate that
    // matches none of them (the sixth-oldest password) passes — the
    // window slides, it is not a lifetime ban.
    when(history.findByUserIdOrderByIdDesc(eq(7L), any(Pageable.class)))
        .thenReturn(List.of(
            new PasswordHistory(7L, "H:recent-pass-5"),
            new PasswordHistory(7L, "H:recent-pass-4"),
            new PasswordHistory(7L, "H:recent-pass-3"),
            new PasswordHistory(7L, "H:recent-pass-2"),
            new PasswordHistory(7L, "H:recent-pass-1")));

    assertThatCode(() -> service.requireNotReused(alice, "sixth-oldest-1"))
        .doesNotThrowAnyException();
  }

  @Test
  void recordOutgoingStoresTheUsersCurrentHash() {
    service.recordOutgoing(alice);

    ArgumentCaptor<PasswordHistory> row = ArgumentCaptor.forClass(PasswordHistory.class);
    verify(history).save(row.capture());
    assertThat(row.getValue().getUserId()).isEqualTo(7L);
    assertThat(row.getValue().getPasswordHash()).isEqualTo("H:current-pass-1");
  }
}
