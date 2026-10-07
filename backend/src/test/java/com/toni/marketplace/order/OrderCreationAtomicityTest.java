package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Atomicity of {@link OrderCreationService#create}: the listing flip
 * (AVAILABLE → RESERVED) and the order insert commit in ONE transaction.
 *
 * <p>This deliberately runs against a real database with a real Spring
 * transaction (no Mockito): rollback semantics cannot be observed with mocks.
 * The failure is injected at the database level — a buyer id with no
 * {@code app_user} row, so the order INSERT fails its foreign key after the
 * flip has already run in the same transaction. Before the transaction
 * boundary was repaired (self-invoked {@code @Transactional} bypassing the
 * proxy), the flip committed in its own repository transaction and this test
 * failed: the listing was left RESERVED with no order row.
 *
 * <p>Same non-transactional test shape as {@link OrderIdempotencyTest}: each
 * test owns its rows via unique usernames and wipes the tables in
 * {@code cleanUp()}.
 */
@SpringBootTest
class OrderCreationAtomicityTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private OrderCreationService creation;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private PasswordService passwords;

  @AfterEach
  void cleanUp() {
    orders.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser(String role) {
    String tag = role + "-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private Item newItem(User seller) {
    return items.save(new Item("Film camera", "Canon AE-1", 88000L, seller.getId()));
  }

  @Test
  void insertFailureAfterItemFlip_rollsBackFlip_noPartialState() {
    User seller = newUser("seller");
    newUser("buyer");
    Item item = newItem(seller);
    // A buyer id with no app_user row: passes every application-level guard
    // (not the seller, no active order) but the order INSERT fails its
    // foreign key AFTER the listing flip ran in the same transaction.
    Long phantomBuyerId = seller.getId() + 999_999L;

    assertThatThrownBy(() -> creation.create(phantomBuyerId, item.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);

    // No partial state: no order row, and the listing flip was rolled back.
    assertThat(orders.count()).isZero();
    Item reloaded = items.findById(item.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(ItemStatus.AVAILABLE);
  }

  @Test
  void happyPath_flipAndInsertCommitTogether() {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);

    OrderDto dto = creation.create(buyer.getId(), item.getId());

    assertThat(dto.status()).isEqualTo(OrderStatus.PENDING);
    assertThat(dto.buyerId()).isEqualTo(buyer.getId());
    assertThat(dto.itemId()).isEqualTo(item.getId());
    assertThat(orders.count()).isEqualTo(1);
    Item reloaded = items.findById(item.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(ItemStatus.RESERVED);
  }
}
