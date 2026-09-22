package io.github.mariusbayizere.fraudshield.auth;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Build prompt H.1/H.2: the auth domain has no framework or I/O imports. */
@AnalyzeClasses(
    packages = "io.github.mariusbayizere.fraudshield.auth",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule domainHasNoFrameworkOrIoDependencies =
      noClasses()
          .that()
          .resideInAPackage("io.github.mariusbayizere.fraudshield.auth.domain")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..", "jakarta..", "java.sql..", "java.net..", "tools.jackson..");

  @ArchTest
  static final ArchRule repositoriesAndServicesDoNotDependOnControllers =
      noClasses()
          .that()
          .resideInAnyPackage(
              "..auth.account..", "..auth.session..", "..auth.apikey..", "..auth.jwt..")
          .should()
          .dependOnClassesThat()
          .haveSimpleNameEndingWith("Controller");
}
