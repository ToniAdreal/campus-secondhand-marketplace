package com.toni.marketplace;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditService;
import com.toni.marketplace.audit.AuditTargetType;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.web.bind.annotation.RestController;

/**
 * Package-layering guardrails (backlog #67; {@code audit/} rules added in
 * backlog #117; newer packages constrained in backlog #279; message/,
 * notification/, moderation/ and item/ held to the auth-web rule in
 * backlog #357; job/ write discipline in backlog #339). The build fails on violation.
 *
 * <p>Intended dependency direction (also documented in the README architecture
 * section):
 *
 * <ul>
 *   <li>{@code common/} is the shared kernel (envelope, exception types,
 *       global exception handler, filters, web config): it depends on no
 *       project package — including the newer {@code dispute/},
 *       {@code offer/}, {@code review/}, {@code notification/},
 *       {@code moderation/} and {@code admin/} packages (#279).
 *   <li>Each domain package ({@code auth/}, {@code item/}, {@code order/},
 *       {@code message/}) depends only on {@code common/} plus its own
 *       classes. {@code order/} in particular never reaches into
 *       {@code auth/} web classes; {@code dispute/}, {@code offer/} and
 *       {@code review/} are held to the same rule (#279) — they may still
 *       use {@code auth/} domain types (e.g. {@code User},
 *       {@code UserRepository}), just never {@code auth/} web classes.
 *       {@code message/}, {@code notification/}, {@code moderation/} and
 *       {@code item/} are held to the same rule (#357) on the same terms:
 *       the only auth/ references in these packages today are domain types
 *       ({@code MessageService}'s {@code UserBlockRepository} /
 *       {@code UserRepository} imports and {@code ItemService}'s
 *       fully-qualified {@code UserBlockRepository} /
 *       {@code SellerFollowRepository} references) and stay allowed.
 *   <li>{@code audit/} (#98) is a leaf trail package: it depends only on
 *       {@code common/} plus its own repository and a {@link java.time.Clock}
 *       — never on a domain package. Domain services ({@code auth/},
 *       {@code order/}) depend on {@code AuditService} to record transitions,
 *       never the reverse.
 *   <li>Inside a domain: controllers delegate to services; repositories stay
 *       behind the service layer — controllers never touch them directly.
 *       The same holds for the audit trail: rows are recorded from the
 *       service layer, inside the audited transition's own transaction.
 *       No controller records, and no controller outside {@code audit/}
 *       depends on {@code AuditService} at all; {@code audit/}'s own ADMIN
 *       controller (#109) only reads through the service.
 *   <li>{@code job/} is the only cross-domain package: scheduled maintenance
 *       sweeps (data retention, orphan-upload cleanup, saved-search matching)
 *       that legitimately touch several domains' stores.
 *   <li>Cross-domain writes are pinned by {@code
 *       only_job_writes_across_multiple_domains} (backlog #339): a class
 *       outside {@code job/} mutates repositories in at most one foreign
 *       domain. The survey behind the rule: reads across domains are
 *       aggregation only (AdminDashboardService, AccountDataExportService,
 *       SellerProfileService, OrderReceiptService resolving usernames and
 *       disputes) and are not constrained by the rule, which counts
 *       {@code save*}/{@code delete*} repository calls only; the lifecycle
 *       writes that do cross one foreign store are the listing-status flips
 *       order/ (create/pay/cancel/complete/refund), dispute/ (resolution),
 *       moderation/ (report/appeal) and auth/ (account closure) perform on
 *       the item/ store. DisputeService is the single documented exception:
 *       dispute resolution refunds the order and releases the listing in one
 *       transition, so it writes dispute/ + order/ + item/ together.
 * <li>STRING-mapped enum fields carry {@code @JdbcTypeCode(SqlTypes.VARCHAR)}
 *     (backlog #384): a bare {@code @Enumerated(STRING)} maps to MySQL ENUM and
 *     fails schema validation against the VARCHAR Flyway column.
 * </ul>
 */
@AnalyzeClasses(
    packages = "com.toni.marketplace",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule common_depends_on_no_project_package =
      noClasses()
          .that()
          .resideInAPackage("com.toni.marketplace.common..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.toni.marketplace.auth..",
              "com.toni.marketplace.item..",
              "com.toni.marketplace.order..",
              "com.toni.marketplace.message..",
              "com.toni.marketplace.audit..",
              "com.toni.marketplace.job..",
              "com.toni.marketplace.dispute..",
              "com.toni.marketplace.offer..",
              "com.toni.marketplace.review..",
              "com.toni.marketplace.notification..",
              "com.toni.marketplace.moderation..",
              "com.toni.marketplace.admin..")
          .because("common/ is the shared kernel; domain knowledge flows inward to it, never out");

  @ArchTest
  static final ArchRule controllers_never_touch_repositories =
      noClasses()
          .that()
          .haveSimpleNameEndingWith("Controller")
          .should()
          .dependOnClassesThat()
          .haveSimpleNameEndingWith("Repository")
          .because("controllers delegate to services; repositories stay behind the service layer");

  @ArchTest
  static final ArchRule order_never_imports_auth_web_classes =
      noClasses()
          .that()
          .resideInAPackage("com.toni.marketplace.order..")
          .should()
          .dependOnClassesThat(authRestControllers())
          .because("order/ must not reach into auth/ web classes; cross-domain needs go through common types");

  @ArchTest
  static final ArchRule dispute_offer_review_never_import_auth_web_classes =
      noClasses()
          .that()
          .resideInAnyPackage(
              "com.toni.marketplace.dispute..",
              "com.toni.marketplace.offer..",
              "com.toni.marketplace.review..")
          .should()
          .dependOnClassesThat(authRestControllers())
          .because(
              "dispute/, offer/ and review/ must not reach into auth/ web classes, mirroring"
                  + " the order/ rule; cross-domain needs go through auth/ domain types or"
                  + " common/ types, never through an auth controller");

  @ArchTest
  static final ArchRule message_notification_moderation_item_never_import_auth_web_classes =
      noClasses()
          .that()
          .resideInAnyPackage(
              "com.toni.marketplace.message..",
              "com.toni.marketplace.notification..",
              "com.toni.marketplace.moderation..",
              "com.toni.marketplace.item..")
          .should()
          .dependOnClassesThat(authRestControllers())
          .because(
              "message/, notification/, moderation/ and item/ must not reach into auth/ web"
                  + " classes, mirroring the order/ and dispute/offer/review/ rules; the"
                  + " only auth/ references in these packages today are domain types"
                  + " (MessageService imports and ItemService fully-qualified references),"
                  + " which stay allowed");

  @ArchTest
  static final ArchRule audit_depends_on_no_domain_package =
      noClasses()
          .that()
          .resideInAPackage("com.toni.marketplace.audit..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.toni.marketplace.auth..",
              "com.toni.marketplace.item..",
              "com.toni.marketplace.order..",
              "com.toni.marketplace.message..",
              "com.toni.marketplace.job..")
          .because(
              "audit/ is a leaf trail: it stores what domain services hand it (its own repository"
                  + " + Clock + common/ types only) and must never learn domain shapes; the"
                  + " dependency runs service -> AuditService, never back");

  @ArchTest
  static final ArchRule controllers_outside_audit_never_depend_on_audit_service =
      noClasses()
          .that()
          .haveSimpleNameEndingWith("Controller")
          .and()
          .resideOutsideOfPackage("com.toni.marketplace.audit..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("com.toni.marketplace.audit.AuditService")
          .because(
              "audit rows are recorded from the service layer inside the audited transition;"
                  + " the only controller that talks to AuditService is audit/'s own ADMIN"
                  + " read controller");

  @ArchTest
  static final ArchRule controllers_never_record_audit_rows =
      noClasses()
          .that()
          .haveSimpleNameEndingWith("Controller")
          .should()
          .callMethod(
              AuditService.class,
              "record",
              long.class,
              AuditAction.class,
              AuditTargetType.class,
              long.class)
          .because(
              "recording joins the audited transition's own transaction in the service layer;"
                  + " AuditLogController serves reads only and must never gain a write path");

  @ArchTest
  static final ArchRule only_job_writes_across_multiple_domains =
      classes()
          .that()
          .resideOutsideOfPackage("com.toni.marketplace.job..")
          .and(notDisputeService())
          .should(mutatingRepositoryCallsTouchAtMostOneForeignDomain())
          .because(
              "cross-domain writes live in job/ sweeps or in a single owning lifecycle"
                  + " transition: a service outside job/ mutates stores in at most one foreign"
                  + " domain (the listing-status flip an order/dispute/moderation/auth"
                  + " transition performs on the item/ store); DisputeService is the one"
                  + " documented exception — dispute resolution refunds the order and"
                  + " releases the listing in a single transition, so it writes dispute/ +"
                  + " order/ + item/ together");

  @ArchTest
  static final ArchRule string_enums_map_to_varchar =
      fields()
          .that()
          .areAnnotatedWith(Enumerated.class)
          .should(beVarcharWhenStringMapped())
          .because(
              "a bare @Enumerated(STRING) makes Hibernate map the column to MySQL ENUM, and"
                  + " schema validation (ddl-auto: validate) then fails the boot against the"
                  + " VARCHAR Flyway column — PasskeyChallenge.purpose did exactly this"
                  + " (backlog #384): every *IT errored 36/36 and the compose backend died at"
                  + " boot on main run 43051043443, while H2 stayed green because it maps"
                  + " the enum to VARCHAR. Every STRING-mapped enum field carries"
                  + " @JdbcTypeCode(SqlTypes.VARCHAR); ORDINAL-mapped fields are out of scope");

  /**
   * STRING-mapped enum fields must carry {@code @JdbcTypeCode(SqlTypes.VARCHAR)}; a field
   * mapped any other way (e.g. ORDINAL) passes unchecked.
   */
  private static ArchCondition<JavaField> beVarcharWhenStringMapped() {
    return new ArchCondition<JavaField>("carry @JdbcTypeCode(SqlTypes.VARCHAR) when STRING-mapped") {
      @Override
      public void check(JavaField field, ConditionEvents events) {
        Enumerated enumerated = field.getAnnotationOfType(Enumerated.class);
        if (enumerated == null || enumerated.value() != EnumType.STRING) {
          return;
        }
        JdbcTypeCode jdbcTypeCode = field.getAnnotationOfType(JdbcTypeCode.class);
        if (jdbcTypeCode == null || jdbcTypeCode.value() != SqlTypes.VARCHAR) {
          events.add(
              SimpleConditionEvent.violated(
                  field,
                  field.getOwner().getName()
                      + "."
                      + field.getName()
                      + " is @Enumerated(STRING) without @JdbcTypeCode(SqlTypes.VARCHAR)"
                      + " — Hibernate maps it to MySQL ENUM and schema validation fails"
                      + " against the VARCHAR Flyway column (backlog #384)"));
        }
      }
    };
  }

  private static DescribedPredicate<JavaClass> notDisputeService() {
    return new DescribedPredicate<JavaClass>("not DisputeService") {
      @Override
      public boolean test(JavaClass javaClass) {
        return !javaClass.getName().equals("com.toni.marketplace.dispute.DisputeService");
      }
    };
  }

  /**
   * Counts the foreign domains whose repositories a class mutates ({@code save*} /
   * {@code delete*} calls only — reads are aggregation, not writes) and fails the
   * class when it touches more than one. A class's own domain never counts.
   */
  private static ArchCondition<JavaClass> mutatingRepositoryCallsTouchAtMostOneForeignDomain() {
    return new ArchCondition<JavaClass>("mutate repositories of at most one foreign domain") {
      @Override
      public void check(JavaClass javaClass, ConditionEvents events) {
        String ownDomain = domainOf(javaClass.getPackageName());
        Map<String, Set<String>> foreignWrites = new TreeMap<>();
        for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
          JavaClass targetOwner = call.getTargetOwner();
          if (!targetOwner.getName().endsWith("Repository")) {
            continue;
          }
          if (!targetOwner.getPackageName().startsWith("com.toni.marketplace.")) {
            continue;
          }
          if (!isMutatingRepositoryMethod(call.getTarget().getName())) {
            continue;
          }
          String domain = domainOf(targetOwner.getPackageName());
          if (!domain.equals(ownDomain)) {
            foreignWrites.computeIfAbsent(domain, d -> new TreeSet<>()).add(targetOwner.getSimpleName());
          }
        }
        if (foreignWrites.size() > 1) {
          events.add(
              SimpleConditionEvent.violated(
                  javaClass,
                  javaClass.getName()
                      + " mutates repositories in more than one foreign domain "
                      + foreignWrites
                      + " — move the sweep into job/ or keep the transition to a single"
                      + " foreign store"));
        }
      }
    };
  }

  private static String domainOf(String packageName) {
    String prefix = "com.toni.marketplace.";
    if (!packageName.startsWith(prefix)) {
      return "";
    }
    String rest = packageName.substring(prefix.length());
    int dot = rest.indexOf('.');
    return dot < 0 ? rest : rest.substring(0, dot);
  }

  private static boolean isMutatingRepositoryMethod(String methodName) {
    return methodName.startsWith("save") || methodName.startsWith("delete");
  }

  private static DescribedPredicate<JavaClass> authRestControllers() {
    // ArchUnit 1.2.1 has no Predicates.annotatedWith; isAnnotatedWith is the equivalent.
    return JavaClass.Predicates.resideInAPackage("com.toni.marketplace.auth..")
        .and(
            new DescribedPredicate<JavaClass>("annotated with @RestController") {
              @Override
              public boolean test(JavaClass javaClass) {
                return javaClass.isAnnotatedWith(RestController.class);
              }
            });
  }
}
