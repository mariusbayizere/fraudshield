package io.github.mariusbayizere.fraudshield.ingest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** H.1/H.2: request validation is pure and controllers hold no decision logic. */
@AnalyzeClasses(
    packages = "io.github.mariusbayizere.fraudshield.ingest",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule validationIsPure =
      noClasses()
          .that()
          .resideInAPackage("..ingest.request..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta..",
              "java.sql..",
              "javax.sql..",
              "io.lettuce..",
              "org.apache.kafka..");

  @ArchTest
  static final ArchRule controllersUseApplicationServicesNotAdapters =
      noClasses()
          .that()
          .resideInAPackage("..ingest.web..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..decision.adapter.jdbc..",
              "..decision.adapter.redis..",
              "..decision.adapter.spool..",
              "..decision.adapter.grpc..",
              "..ingest.idempotency..",
              "java.sql.Connection");

  @ArchTest
  static final ArchRule applicationDoesNotDependOnTheWebLayer =
      noClasses()
          .that()
          .resideInAPackage("..ingest.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..ingest.web..", "org.springframework.web..", "jakarta.servlet..");
}
