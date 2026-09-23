package io.github.mariusbayizere.fraudshield.rules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** H.1: the DSL is framework-free; JSON parsing lives in the adapter package. */
@AnalyzeClasses(
    packages = "io.github.mariusbayizere.fraudshield.rules",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule dslHasNoFrameworkOrIoDependencies =
      noClasses()
          .that()
          .resideInAPackage("..rules.dsl..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "tools.jackson..",
              "com.fasterxml..",
              "org.springframework..",
              "java.sql..",
              "java.net..",
              "java.nio..",
              "java.util.regex..",
              "..rules.json..");
}
