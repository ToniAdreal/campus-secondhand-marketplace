package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Repository integration test against a real MySQL 8 (Testcontainers).
 * Requires Docker; runs in CI via {@code mvn verify -Pintegration},
 * not in the default unit-test phase.
 */
@Testcontainers
@SpringBootTest
class ItemRepositoryMySqlIT {

  @Container
  @ServiceConnection
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @Autowired
  private ItemRepository items;

  @Test
  void flywayMigratesAndPersistsAgainstRealMySql() {
    Item saved = items.save(new Item("Dorm fridge", "Mini fridge, quiet", 29900L, 7L));

    assertThat(saved.getId()).isNotNull();
    assertThat(items.findById(saved.getId()))
        .hasValueSatisfying(i -> assertThat(i.getTitle()).isEqualTo("Dorm fridge"));
  }

  @Test
  void optimisticLockVersionIsAssignedOnPersist() {
    Item saved = items.save(new Item("Bike", "City bike", 45000L, 7L));

    assertThat(saved.getVersion()).isNotNull();
  }
}
