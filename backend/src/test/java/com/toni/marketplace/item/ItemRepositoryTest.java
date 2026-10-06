package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class ItemRepositoryTest {

  @Autowired
  private ItemRepository items;

  @Test
  void persistsAndFindsById() {
    Item saved = items.save(new Item("Used ThinkPad", "T480, good battery", 129900L, 7L));

    assertThat(saved.getId()).isNotNull();
    assertThat(items.findById(saved.getId()))
        .hasValueSatisfying(i -> {
          assertThat(i.getTitle()).isEqualTo("Used ThinkPad");
          assertThat(i.getStatus()).isEqualTo(ItemStatus.AVAILABLE);
        });
  }

  @Test
  void optimisticLockVersionIsAssignedOnPersist() {
    Item saved = items.save(new Item("Bike", "City bike", 45000L, 7L));

    assertThat(saved.getVersion()).isNotNull();
  }

  @Test
  void findByStatusFiltersCorrectly() {
    Item sold = new Item("Lamp", "Desk lamp", 8000L, 7L);
    sold.setStatus(ItemStatus.SOLD);
    items.save(sold);
    items.save(new Item("Chair", "Office chair", 30000L, 7L));

    List<Item> available = items.findByStatus(ItemStatus.AVAILABLE);

    assertThat(available).hasSize(1);
    assertThat(available.get(0).getTitle()).isEqualTo("Chair");
  }
}
