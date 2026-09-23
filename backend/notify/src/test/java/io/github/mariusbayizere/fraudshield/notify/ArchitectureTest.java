package io.github.mariusbayizere.fraudshield.notify;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** H.1: the D-25 and D-43 policies and the signature scheme are pure; I/O stays in adapters. */
@AnalyzeClasses(
    packages = "io.github.mariusbayizere.fraudshield.notify",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule policiesArePure =
      noClasses()
          .that()
          .haveSimpleNameEndingWith("Policy")
          .or()
          .haveSimpleName("LocalTimes")
          .or()
          .haveSimpleName("ReferenceCodes")
          .or()
          .haveSimpleName("Gsm7")
          .or()
          .haveSimpleName("WebhookSignatures")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "java.sql..", "java.net..", "javax.sql..", "org.apache.kafka..", "io.lettuce..");

  @ArchTest
  static final ArchRule notifyDoesNotReachIntoDecisionAdaptersExceptTheEventCodec =
      noClasses()
          .that()
          .resideInAPackage("..notify..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..decision.adapter.jdbc..", "..decision.adapter.redis..",
              "..decision.adapter.spool..", "..decision.adapter.grpc..");
}
