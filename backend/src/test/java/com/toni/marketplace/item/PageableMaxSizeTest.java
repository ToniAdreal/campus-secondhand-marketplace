package com.toni.marketplace.item;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Page-size clamp (backlog #75): {@code spring.data.web.pageable.max-page-size=100}
 * in application.yml bounds {@code ?size=} on every Pageable-backed list endpoint
 * ({@code /api/items}, {@code /api/orders}, {@code /api/seller/orders}).
 * An unbounded {@code ?size=10000} must not run an unbounded row scan.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users and items it creates
class PageableMaxSizeTest {

  private static final int SEED_COUNT = 120;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private String userToken;

  @BeforeEach
  void seedSellerAndItems() {
    User seller = users.save(new User("seller-pgsize", "seller-pgsize@example.com",
        passwords.encode("seller-password")));
    userToken = jwt.createTokenPair(seller).accessToken();
    items.saveAll(IntStream.range(0, SEED_COUNT)
        .mapToObj(i -> new Item("Listing " + i, "description " + i, 1000L, seller.getId()))
        .toList());
  }

  @Test
  void oversizedSizeIsClampedToOneHundred() throws Exception {
    mockMvc.perform(get("/api/items?size=10000")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        // the resolver clamped the requested size to 100 …
        .andExpect(jsonPath("$.data.size").value(100))
        .andExpect(jsonPath("$.data.content.length()").value(100))
        // … while the total is untouched
        .andExpect(jsonPath("$.data.totalElements").value(SEED_COUNT));
  }

  @Test
  void sizeUnderTheMaxPassesThroughUnchanged() throws Exception {
    mockMvc.perform(get("/api/items?size=50")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.size").value(50))
        .andExpect(jsonPath("$.data.content.length()").value(50))
        .andExpect(jsonPath("$.data.totalElements").value(SEED_COUNT));
  }
}
