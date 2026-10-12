package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Stale optimistic-lock writes on listings (backlog #147): two tabs editing
 * the same listing concurrently used to surface the generic 500 envelope,
 * because {@code ItemService} relied on {@code Item.@Version} failing at
 * commit with nothing mapping the failure. The listing writes now flush
 * inside their transactions and {@code GlobalExceptionHandler} maps
 * {@code ObjectOptimisticLockingFailureException} to 409 "listing was
 * modified — reload and retry".
 *
 * <p>Deliberately NOT {@code @Transactional} (mirroring
 * {@code OrderExpiryTest}): each request must commit its own transaction
 * for a version race to be real. The race itself is forced deterministically
 * in a single thread — a test-only aspect around
 * {@link ItemRepository#findById} lets a "concurrent" edit commit through a
 * separate {@link EntityManager} right after the service's own read, so the
 * service works on a stale copy of the row and its flush is the second
 * commit, which loses. That is exactly the two-managed-copies shape of
 * {@code OrderRepositoryTest}'s stale test, driven end-to-end through
 * {@code PATCH /api/items/{id}} (and its {@code /status} sibling).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ItemOptimisticLockConflictTest {

  private static final AtomicLong SEQ = new AtomicLong();

  /**
   * Test-only seam that commits a concurrent edit mid-request. Armed per
   * test via {@link #arm(long, String)}; disarmed (and its read counter
   * reset) after each test. Lives only in this class's application context.
   */
  @Aspect
  static class ConcurrentEditAspect {

    private final EntityManagerFactory emf;
    private final AtomicInteger readsOfTarget = new AtomicInteger();
    private volatile Long targetId;
    private volatile String winningTitle;

    ConcurrentEditAspect(EntityManagerFactory emf) {
      this.emf = emf;
    }

    void arm(long itemId, String title) {
      readsOfTarget.set(0);
      this.winningTitle = title;
      this.targetId = itemId;
    }

    void disarm() {
      this.targetId = null;
      this.winningTitle = null;
    }

    /**
     * {@code findById} runs twice on a listing PATCH — first from
     * {@code ItemSecurity} (ownership check), then from {@code ItemService}
     * (the write's own read) — so the concurrent edit lands on the second
     * call: bumping any earlier would just hand the service a fresh copy,
     * and the request would (correctly) succeed.
     */
    @Around("execution(* com.toni.marketplace.item.ItemRepository.findById(..))")
    Object afterServiceRead(ProceedingJoinPoint joinPoint) throws Throwable {
      Object result = joinPoint.proceed();
      Long armedId = targetId;
      if (armedId != null && result instanceof Optional<?> optional
          && optional.isPresent() && optional.get() instanceof Item found
          && found.getId().equals(armedId)
          && readsOfTarget.incrementAndGet() == 2) {
        commitConcurrentEdit(armedId, winningTitle);
      }
      return result;
    }

    /** Commits an edit to the row through its own persistence context. */
    private void commitConcurrentEdit(long itemId, String title) {
      EntityManager em = emf.createEntityManager();
      try {
        em.getTransaction().begin();
        Item concurrent = em.find(Item.class, itemId);
        concurrent.setTitle(title);
        em.getTransaction().commit();
      } finally {
        em.close();
      }
    }
  }

  @TestConfiguration
  @EnableAspectJAutoProxy
  static class ConcurrentEditAspectConfig {

    @Bean
    ConcurrentEditAspect concurrentEditAspect(EntityManagerFactory emf) {
      return new ConcurrentEditAspect(emf);
    }
  }

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository itemRepo;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private ConcurrentEditAspect concurrentEdit;

  @AfterEach
  void cleanUp() {
    concurrentEdit.disarm();
    refreshTokens.deleteAll();
    itemRepo.deleteAll();
    users.deleteAll();
  }

  private User newSeller() {
    String tag = "seller-ol-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com",
        passwords.encode("seller-password")));
  }

  private Item seedItem(User seller) {
    return itemRepo.save(new Item("Used ThinkPad T480s", "good battery", 25000L,
        seller.getId()));
  }

  @Test
  void staleEditOnPatchAnswers409AndLosesToTheConcurrentEdit() throws Exception {
    User seller = newSeller();
    Item item = seedItem(seller);
    String token = jwt.createTokenPair(seller).accessToken();
    concurrentEdit.arm(item.getId(), "Concurrent edit wins");

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"My stale edit\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message")
            .value("listing was modified — reload and retry"));

    // The losing edit left no trace: the row still holds the winning edit.
    Item reloaded = itemRepo.findById(item.getId()).orElseThrow();
    assertThat(reloaded.getTitle()).isEqualTo("Concurrent edit wins");
    assertThat(reloaded.getStatus()).isEqualTo(ItemStatus.AVAILABLE);
  }

  @Test
  void staleMarkSoldAnswers409AndTheListingStaysAvailable() throws Exception {
    User seller = newSeller();
    Item item = seedItem(seller);
    String token = jwt.createTokenPair(seller).accessToken();
    concurrentEdit.arm(item.getId(), "Concurrent edit wins");

    mockMvc.perform(patch("/api/items/{id}/status", item.getId())
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SOLD\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message")
            .value("listing was modified — reload and retry"));

    Item reloaded = itemRepo.findById(item.getId()).orElseThrow();
    assertThat(reloaded.getTitle()).isEqualTo("Concurrent edit wins");
    assertThat(reloaded.getStatus()).isEqualTo(ItemStatus.AVAILABLE);
  }

  @Test
  void uncontendedEditStillSucceeds() throws Exception {
    // Control: with no concurrent edit armed, the same PATCH path still
    // commits normally — the 409s above come from the forced race, not
    // from the harness.
    User seller = newSeller();
    Item item = seedItem(seller);
    String token = jwt.createTokenPair(seller).accessToken();

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Solo edit\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.title").value("Solo edit"));

    assertThat(itemRepo.findById(item.getId()).orElseThrow().getTitle())
        .isEqualTo("Solo edit");
  }
}
