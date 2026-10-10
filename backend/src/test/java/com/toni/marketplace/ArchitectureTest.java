package com.toni.marketplace;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditService;
import com.toni.marketplace.audit.AuditTargetType;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

/**
 * Package-layering guardrails (backlog #67; {@code audit/} rules added in
 * backlog #117). The build fails on violation.
 *
 * <p>Intended dependency direction (also documented in the README architecture
 * section):
 *
 * <ul>
 *   <li>{@code common/} is the shared kernel (envelope, exception types,
 *       global exception handler, filters, web config): it depends on no
 *       project package.
 *   <li>Each domain package ({@code auth/}, {@code item/}, {@code order/},
 *       {@code message/}) depends only on {@code common/} plus its own
 *       classes. {@code order/} in particular never reaches into
 *       {@code auth/} web classes.
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
 *       sweeps (data retention) that legitimately touch several domains'
 *       stores.
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
              "com.toni.marketplace.job..")
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
