package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Proves the two concurrency contracts of the order table (Flyway V4):
 * {@code @Version} optimistic locking on transitions, and the
 * {@code uq_order_active_item} guard — at most one active (PENDING/PAID)
 * order per item, while terminal (COMPLETED/CANCELLED) history is unbounded.
 */
@DataJpaTest
class OrderRepositoryTest {

  @Autowired
  private OrderRepository orders;

  @Autowired
  private ItemRepository items;

  @Autowired
  private UserRepository users;

  @Autowired
  private TestEntityManager em;

  private Item item;
  private Long buyerId;

  @BeforeEach
  void setUp() {
    item = items.save(new Item("Film camera", "Canon AE-1, tested", 88000L, 9L));
    buyerId = users.save(new User("buyer1", "buyer1@example.com", "hash")).getId();
  }

  @Test
  void persistsOrderWithVersionAndActiveGuard() {
    Order saved = orders.saveAndFlush(new Order(item, buyerId, 88000L));

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getVersion()).isNotNull();
    assertThat(saved.getStatus()).isEqualTo(OrderStatus.PENDING);
    // The guard column is DB-generated: the freshly persisted instance has
    // not been refreshed, so re-read to see the computed value.
    em.clear();
    assertThat(orders.findById(saved.getId()))
        .hasValueSatisfying(o -> assertThat(o.getActiveItemId()).isEqualTo(item.getId()));
  }

  @Test
  void staleTransitionFailsWithOptimisticLocking() {
    Order saved = orders.saveAndFlush(new Order(item, buyerId, 88000L));
    em.detach(saved); // stale copy pinned at version 0

    Order fresh = orders.findById(saved.getId()).orElseThrow();
    fresh.setStatus(OrderStatus.PAID);
    orders.saveAndFlush(fresh); // version -> 1

    saved.setStatus(OrderStatus.CANCELLED);
    assertThatThrownBy(() -> orders.saveAndFlush(saved))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  @Test
  void secondActiveOrderForSameItemIsRejected() {
    orders.saveAndFlush(new Order(item, buyerId, 88000L));

    Order second = new Order(item, buyerId, 88000L);
    assertThatThrownBy(() -> orders.saveAndFlush(second))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void terminalOrderReleasesTheItemForANewOrder() {
    Order first = orders.save(new Order(item, buyerId, 88000L));
    first.setStatus(OrderStatus.COMPLETED);
    orders.saveAndFlush(first);

    assertThat(orders.existsByItem_IdAndStatusIn(
        item.getId(), List.of(OrderStatus.PENDING, OrderStatus.PAID))).isFalse();

    Order second = orders.saveAndFlush(new Order(item, buyerId, 88000L));
    assertThat(second.getId()).isNotNull();
    assertThat(orders.existsByItem_IdAndStatusIn(
        item.getId(), List.of(OrderStatus.PENDING, OrderStatus.PAID))).isTrue();
  }

  @Test
  void multipleTerminalOrdersForSameItemAreAllowed() {
    // A plain UNIQUE(item_id, status) would collide here; the guard column
    // must only constrain active orders.
    Order a = new Order(item, buyerId, 88000L);
    a.setStatus(OrderStatus.CANCELLED);
    Order b = new Order(item, buyerId, 88000L);
    b.setStatus(OrderStatus.CANCELLED);

    orders.saveAllAndFlush(List.of(a, b));

    em.clear();
    assertThat(orders.findById(a.getId()))
        .hasValueSatisfying(o -> assertThat(o.getActiveItemId()).isNull());
    assertThat(orders.findById(b.getId()))
        .hasValueSatisfying(o -> assertThat(o.getActiveItemId()).isNull());
  }

  @Test
  void findByBuyerIdReturnsBuyersOrders() {
    Long otherBuyer = users.save(new User("buyer2", "buyer2@example.com", "hash")).getId();
    orders.save(new Order(item, buyerId, 88000L));
    Order other = new Order(item, otherBuyer, 88000L);
    other.setStatus(OrderStatus.CANCELLED);
    orders.save(other);

    List<Order> mine = orders.findByBuyerId(buyerId);

    assertThat(mine).hasSize(1);
    assertThat(mine.get(0).getBuyerId()).isEqualTo(buyerId);
  }
}
