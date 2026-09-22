package io.github.mariusbayizere.fraudshield.audit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Build prompt H.1/H.2: the audit domain has no framework or I/O imports; adapters depend on it.
 */
@AnalyzeClasses(
    packages = "io.github.mariusbayizere.fraudshield.audit",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule domainHasNoFrameworkOrIoDependencies =
      noClasses()
          .that()
          .resideInAPackage("io.github.mariusbayizere.fraudshield.audit")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..", "jakarta..", "java.sql..", "tools.jackson..");

  @ArchTest
  static final ArchRule noPackageCycles =
      slices()
          .matching("io.github.mariusbayizere.fraudshield.audit.(*)..")
          .should()
          .beFreeOfCycles();
}
