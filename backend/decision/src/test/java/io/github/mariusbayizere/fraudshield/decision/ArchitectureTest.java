package io.github.mariusbayizere.fraudshield.decision;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** H.1/H.2: domain and application code import no framework, network or storage client. */
@AnalyzeClasses(
    packages = "io.github.mariusbayizere.fraudshield.decision",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  private static final String[] INFRASTRUCTURE = {
    "org.springframework..",
    "jakarta..",
    "java.sql..",
    "java.net..",
    "org.apache.kafka..",
    "io.lettuce..",
    "io.grpc..",
    "com.google.protobuf..",
    "io.github.mariusbayizere.fraudshield.contracts..",
    "tools.jackson..",
    "io.github.resilience4j..",
    "io.micrometer.."
  };

  @ArchTest
  static final ArchRule domainIsPure =
      noClasses()
          .that()
          .resideInAPackage("..decision.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(INFRASTRUCTURE);

  @ArchTest
  static final ArchRule domainDoesNotDependOnApplicationOrAdapters =
      noClasses()
          .that()
          .resideInAPackage("..decision.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..decision.application..", "..decision.adapter..");

  @ArchTest
  static final ArchRule applicationUsesPortsNotAdapters =
      noClasses()
          .that()
          .resideInAPackage("..decision.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(INFRASTRUCTURE)
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("..decision.adapter..");
}
