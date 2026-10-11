package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLog;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.audit.AuditTargetType;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN category writes are audited (backlog #132): create, rename
 * ({@code PATCH /api/categories/{id}} — the update path lands with this
 * backlog item; before it, categories could only be created and deleted)
 * and delete each append exactly one {@code audit_log} row
 * ({@code CATEGORY_CREATED} / {@code CATEGORY_UPDATED} /
 * {@code CATEGORY_DELETED}, actor = the acting ADMIN, targetType
 * {@code CATEGORY}, targetId = the category id) inside the write's own
 * transaction. Rejected writes (non-admin 403, duplicate-name 409,
 * in-use-delete 409, unknown-id 404) write no row, and the new actions
 * are filterable on {@code GET /api/admin/audit-log}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users, categories and audit rows it creates
class CategoryAuditTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private CategoryRepository categoryRepo;

  @Autowired
  private ItemRepository itemRepo;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private User seller;
  private User admin;
  private String userToken;
  private String adminToken;

  @BeforeEach
  void createUsersAndTokens() {
    seller = users.save(new User("cataudit-seller", "cataudit-seller@example.com",
        passwords.encode("seller-password-1")));
    admin = new User("cataudit-admin", "cataudit-admin@example.com",
        passwords.encode("admin-password-1"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);

    userToken = jwt.createTokenPair(seller).accessToken();
    adminToken = jwt.createTokenPair(admin).accessToken();
  }

  private List<AuditLog> rowsFor(long categoryId) {
    return auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(
        AuditTargetType.CATEGORY, categoryId);
  }

  @Test
  void adminCreateWritesExactlyOneAuditRow() throws Exception {
    mockMvc.perform(post("/api/categories")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Audit Gadgets\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.slug").value("audit-gadgets"));

    Category created = categoryRepo.findBySlug("audit-gadgets").orElseThrow();
    List<AuditLog> rows = rowsFor(created.getId());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.CATEGORY_CREATED);
    assertThat(rows.get(0).getActorUserId()).isEqualTo(admin.getId());
  }

  @Test
  void adminUpdateWritesExactlyOneAuditRowAndRenames() throws Exception {
    Category temp = categoryRepo.save(new Category("Old Name", "old-name"));

    mockMvc.perform(patch("/api/categories/{id}", temp.getId())
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"New Name\",\"slug\":\"new-name\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("New Name"))
        .andExpect(jsonPath("$.data.slug").value("new-name"));

    Category renamed = categoryRepo.findById(temp.getId()).orElseThrow();
    assertThat(renamed.getName()).isEqualTo("New Name");
    assertThat(renamed.getNameLower()).isEqualTo("new name");
    assertThat(renamed.getSlug()).isEqualTo("new-name");

    List<AuditLog> rows = rowsFor(temp.getId());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.CATEGORY_UPDATED);
    assertThat(rows.get(0).getActorUserId()).isEqualTo(admin.getId());
  }

  @Test
  void adminUpdateWithoutSlugKeepsTheCurrentSlug() throws Exception {
    Category temp = categoryRepo.save(new Category("Keep Slug", "keep-slug"));

    mockMvc.perform(patch("/api/categories/{id}", temp.getId())
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Renamed Only\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("Renamed Only"))
        .andExpect(jsonPath("$.data.slug").value("keep-slug"));

    assertThat(rowsFor(temp.getId()))
        .extracting(AuditLog::getAction)
        .containsExactly(AuditAction.CATEGORY_UPDATED);
  }

  @Test
  void adminDeleteWritesExactlyOneAuditRowThatSurvivesTheDeletion() throws Exception {
    Category temp = categoryRepo.save(new Category("Doomed Audit", "doomed-audit"));

    mockMvc.perform(delete("/api/categories/{id}", temp.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNoContent());

    assertThat(categoryRepo.findById(temp.getId())).isEmpty();
    // No foreign keys on audit_log: the trail row outlives its target.
    List<AuditLog> rows = rowsFor(temp.getId());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.CATEGORY_DELETED);
    assertThat(rows.get(0).getActorUserId()).isEqualTo(admin.getId());
  }

  @Test
  void nonAdminCreateIs403AndWritesNoAuditRow() throws Exception {
    mockMvc.perform(post("/api/categories")
            .header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Sneaky Gadgets\"}"))
        .andExpect(status().isForbidden());

    assertThat(auditLog.countByAction(AuditAction.CATEGORY_CREATED)).isZero();
  }

  @Test
  void duplicateNameCreateIs409AndWritesNoAuditRow() throws Exception {
    // "Electronics" is seeded by Flyway V3 — the case variant must collide.
    mockMvc.perform(post("/api/categories")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"electronics\"}"))
        .andExpect(status().isConflict());

    assertThat(auditLog.countByAction(AuditAction.CATEGORY_CREATED)).isZero();
  }

  @Test
  void duplicateNameUpdateIs409AndWritesNoAuditRow() throws Exception {
    Category electronics = categoryRepo.findBySlug("electronics").orElseThrow();
    Category temp = categoryRepo.save(new Category("Rename Me", "rename-me"));

    mockMvc.perform(patch("/api/categories/{id}", temp.getId())
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Electronics\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("category name already exists"));

    assertThat(categoryRepo.findById(temp.getId()).orElseThrow().getName())
        .isEqualTo("Rename Me");
    assertThat(auditLog.countByAction(AuditAction.CATEGORY_UPDATED)).isZero();
    assertThat(rowsFor(electronics.getId())).isEmpty();
  }

  @Test
  void inUseDeleteIs409AndWritesNoAuditRow() throws Exception {
    Category electronics = categoryRepo.findBySlug("electronics").orElseThrow();
    Item item = new Item("Used bike", "good condition", 50000L, seller.getId());
    item.setCategory(electronics);
    itemRepo.save(item);

    mockMvc.perform(delete("/api/categories/{id}", electronics.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isConflict());

    assertThat(auditLog.countByAction(AuditAction.CATEGORY_DELETED)).isZero();
    assertThat(rowsFor(electronics.getId())).isEmpty();
  }

  @Test
  void updateUnknownCategoryIs404AndWritesNoAuditRow() throws Exception {
    mockMvc.perform(patch("/api/categories/{id}", 999999L)
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Ghost\"}"))
        .andExpect(status().isNotFound());

    assertThat(auditLog.countByAction(AuditAction.CATEGORY_UPDATED)).isZero();
  }

  @Test
  void nonAdminUpdateIs403AndWritesNoAuditRow() throws Exception {
    Category temp = categoryRepo.save(new Category("Hands Off", "hands-off"));

    mockMvc.perform(patch("/api/categories/{id}", temp.getId())
            .header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Taken Over\"}"))
        .andExpect(status().isForbidden());

    assertThat(categoryRepo.findById(temp.getId()).orElseThrow().getName())
        .isEqualTo("Hands Off");
    assertThat(rowsFor(temp.getId())).isEmpty();
  }

  @Test
  void categoryActionsAreFilterableOnTheAdminAuditLogRead() throws Exception {
    mockMvc.perform(post("/api/categories")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Filterable Finds\"}"))
        .andExpect(status().isCreated());
    Category created = categoryRepo.findBySlug("filterable-finds").orElseThrow();

    mockMvc.perform(get("/api/admin/audit-log")
            .header("Authorization", "Bearer " + adminToken)
            .param("action", "CATEGORY_CREATED")
            .param("targetType", "CATEGORY")
            .param("targetId", String.valueOf(created.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].action").value("CATEGORY_CREATED"))
        .andExpect(jsonPath("$.data.content[0].targetType").value("CATEGORY"))
        .andExpect(jsonPath("$.data.content[0].actorUserId").value(admin.getId()));
  }
}
