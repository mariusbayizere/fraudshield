package io.github.mariusbayizere.fraudshield.common;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Build prompt H.1/H.2: shared domain primitives stay free of framework and I/O imports. */
@AnalyzeClasses(
    packages = "io.github.mariusbayizere.fraudshield.common",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule commonHasNoFrameworkOrIoDependencies =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta..",
              "java.sql..",
              "java.net..",
              "org.apache.kafka..",
              "io.lettuce..");

  @ArchTest
  static final ArchRule commonHasNoPackageCycles =
      slices()
          .matching("io.github.mariusbayizere.fraudshield.common.(*)..")
          .should()
          .beFreeOfCycles();
}
