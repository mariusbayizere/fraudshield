# M9 images: independent review 1

Scope: `git diff 4e0a7bc..HEAD` on `m9/images` (b745c83, 581a752), excluding
`docs/traceability/requirements_matrix.md`, together with the mechanisms around it: ADR 0019 and its
amendment, the contract (`getDeploymentEnvironment`, `DeploymentEnvironment`, authorisation matrix),
`backend/auth` security, every way the API starts in tests, the Dockerfile and its ignore file,
the start test and the `supply-chain` workflow.

Method: I read the code, git history, the built jars in `backend/ingest/target` and
`backend/deployment-guard/target` (unzip only), the Docker Hub registry API (anonymous token, no
identifiers sent), and Maven Central. I ran no Maven, Docker or Testcontainers. "Unverified" means
I did not confirm it by execution.

Counts: **2 BLOCKER, 0 MAJOR, 5 MINOR, 8 NOTE.**

## BLOCKER

### B1. Every API test against the harness now refuses to start: the harness database is seeded with a synthetic institution and the tests run under `test`

- Where: `backend/ingest/src/test/java/.../ingest/api/ApiHarness.java:55` (`PROFILE = "test"`) and
  `:145-146` (`DB.institution(Fixtures.INSTITUTION)`); `backend/decision/src/test/java/.../testing/TestDatabase.java:169-171`
  (`INSERT ... synthetic ... VALUES (..., true)`);
  `backend/deployment-guard/src/main/java/.../deployment/SyntheticDataFlag.java:221-229`;
  `SyntheticDataGuard.java:52-53`.
- Mechanism: `ingest` now carries the guard (`ingest/pom.xml:18-22`), and `META-INF/spring.factories`
  registers `SyntheticDataGuard` in every context started from the `ingest` test classpath. Each
  `@SpringBootTest` registers `spring.datasource.url`/`username`/`password` through
  `ApiHarness.properties` (`@DynamicPropertySource`). Those properties are added to the environment by a
  context customiser before `ApplicationPreparedEvent`, so the guard reaches the harness database as
  `fs_app` (which has EXECUTE on `deployment_has_synthetic_data()`, `V11__grants.sql:74`). The function
  returns true because the harness institution is `synthetic = true`. The active profiles are `{test}`, which is not a
  subset of `{dev, demo}`, so `checkProfiles` throws "Refusing to start: the database holds synthetic
  demo data".
- Affected: `IngestApiTest` (including the new
  `theEnvironmentIsPublicAndCarriesOnlyTheSyntheticDataFlag`), `ApiDocsTest`, `AssembledApplicationTest`,
  `ResilienceApiTest`, `RateLimitApiTest`, `DecisionLatencyBenchmark`. All are `requires-docker`.
  CI's `java` job runs `./mvnw -B -ntp verify` with no `excludedGroups` (`.github/workflows/ci.yml:65`),
  so it runs them. The only ingest reports in `backend/ingest/target/surefire-reports` are `ArchitectureTest`
  and `EnvironmentControllerTest`, so these tests have not been run on this branch. The failure follows from
  the code; I have not seen it in a run.
- Other start paths: no `DataSource` bean is defined outside Spring Boot's auto-configuration in the API
  or its tests. The vault `PGSimpleDataSource` in `NotificationWiring.java:219-229` is not a bean, and
  `StaffIdentityWiringTest`'s `AbstractDataSource` is a local object. So the guard's "exactly one data
  source" rule holds. `auth`, `admin`, `analyst` and `audit` do not depend on `deployment-guard` or
  `ingest`, so their test applications are unaffected. `AssembledApplicationTest:253-261` runs
  `AuditVerifyCommand` directly, not through Spring Boot.
- Fix: make the harness database clean of the marker. For example, after `DB.institution(...)` in
  `ApiHarness`, run `UPDATE fraudshield.institutions SET synthetic = false` as superuser, or give
  `TestDatabase.institution` a `synthetic` parameter. Then assert the exact flag (see m1). Do not move the
  API tests to `dev`: that would test a different profile path. Add one assembled-API test that a
  seeded database under `test` refuses to start, so the API's new fail-closed path is covered in the
  assembled application and not only in `persistence`.

### B2. The image build fails at `./mvnw`: the build image has no `unzip`, so the wrapper downloads the `.tar.gz` and checks it against the `.zip`'s SHA-256

- Where: `backend/Dockerfile:70-73`; `backend/.mvn/wrapper/maven-wrapper.properties:3-4`
  (`distributionType=only-script`, zip URL, `distributionSha256Sum=5af3b743...`); `backend/mvnw:179-181`
  (no `unzip` switches the URL to `.tar.gz`) and `:226-250` (the unchanged sum is checked against the
  downloaded file and fails with "Failed to validate Maven distribution SHA-256").
- Evidence: I listed the base layers of the amd64 image of
  `eclipse-temurin@sha256:10721595...` (`21.0.12.1_1-jdk-noble`). They contain `curl`, `wget`, `tar`
  and `sha256sum`, but no `unzip`. The image history's `apt-get install` installs curl, wget, gnupg,
  fontconfig, ca-certificates, p11-kit, binutils, tzdata and locales, but not unzip. On Maven Central,
  `apache-maven-3.9.16-bin.zip` has SHA-256 `5af3b743...`, which is the configured value, and
  `-bin.tar.gz` has `80ffca22...`.
- Failure: in the `images` job, `Build` fails in the `mvnw` step before Maven runs. No image, no
  start test, no scan.
- Fix: install `unzip` in the build stage
  (`RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*`
  before the `mvnw` step). Alternatively, build from a pinned Maven image, which would bypass the wrapper's
  checksum and needs its own pinning. Do not drop `distributionSha256Sum`.

## MAJOR

None.

## MINOR

### m1. The assembled-API endpoint test accepts either value of the flag

`IngestApiTest.java:629` matches `\{"synthetic_data":(true|false)\}`. A guard that publishes the wrong
flag, or an `EnvironmentController` wired to a different status bean, would pass. Fix: once B1 is fixed,
assert the exact body the harness implies, for example `{"synthetic_data":false}` for a clean database
under `test`.

### m2. The start test passes when the JDBC driver is missing, or on any other `SQLException`

`infrastructure/docker/start-tests/fraudshield-api.sh:732` expects only "Refusing to start: cannot check
the database for synthetic demo data (ADR 0019)". `SyntheticDataGuard.java:91-98` prints that prefix for
every `SQLException` outside the not-migrated states. "No suitable driver found" (SQLState 08001)
therefore produces the same line. So an image missing `postgresql-*.jar` would pass the step that is
meant to prove its classpath. Fix: also expect pgjdbc's connection-refused text, such as
`Connection to 127.0.0.1:1 refused`.

### m3. The start test's comment claims more than the test shows, and points to a test that does not exist

`fraudshield-api.sh:5-10`:

- The line "proves the image, its launcher, its classpath" holds only for the classes loaded before
  `ApplicationPreparedEvent`. Nothing after that point is exercised: bean creation, the packaged contract
  (`fraudshield/contracts/*.yaml`), the country packs, Tomcat's base directory on the read-only
  filesystem, or the spool.
- The comment also says serving requests "is the compose stack's start test (`fraudshield-api`
  healthy in the `full` profile)". `docker-compose.yml` has no `fraudshield-api` service. Its services
  are timescaledb, pii-vault, pii-vault-migrate, redis, kafka, object-store, object-store-init, mlflow,
  mailpit and wiremock.

Fix: narrow the sentence to what the test shows. Name the missing compose service as open work (a
backlog item) rather than as an existing test.

### m4. The guard's own connection has no timeouts, and the API now waits on it at start-up

`SyntheticDataGuard.java:86` uses `DriverManager.getConnection(url, credentials)`. The API's Hikari
settings (`connectTimeout: 2`, `socketTimeout: 10`, `connection-timeout: 2000`, `ingest/application.yml:13-19`)
do not apply to it. pgjdbc's defaults are a 10 s TCP connect timeout and no socket or login timeout. A
database that accepts TCP but does not answer would stall start-up until the Kubernetes `startupProbe`
gives up (36 × 5 s = 180 s, `api.yaml:67-70`), and then again on every restart. The behaviour already
existed in the database tool; this change brings it into the API. Fix: set `connectTimeout`,
`socketTimeout` and `loginTimeout` on the guard's `Properties` (for example 5/10/10 s). Keep failing
closed on timeout.

### m5. `EnvironmentController` follows only half of the staff-controller convention

`EnvironmentController.java:41` has `@ContractOperation("getDeploymentEnvironment")` but no
`@PreAuthorize("permitAll()")`. The handlers in `admin` and `analyst` carry both, and their inspectors
(`AuthorisationMatrixTest`, `AnalystAuthorisationTest`) reject a handler with only one. No inspector
covers handlers assembled in `ingest`, so this passes. Access is correct today: `ContractAuthorizationManager`
grants it at URL level because the matrix marks the operation `public: true`, and anonymous access is
checked in `IngestApiTest`. Fix: add `@PreAuthorize("permitAll()")`. Optionally, extend one assembled-API
test to check every `@ContractOperation` handler in the API against the matrix.

## NOTE

1. **Stale comments in the moved tests.** `SyntheticDataGuardCoverageTest.java:79-80` ("The module's
   application.yml has a default URL") and the `DemoSeedGuardTest` comment about `application.yml`
   ranking above default properties describe `persistence`. `deployment-guard` has no `application.yml`.
   The tests still check what they claim, because the command-line arguments are explicit, but the
   comments are wrong in the new module.
2. **Coverage gate.** The local `deployment-guard/target/site/jacoco/jacoco.csv` (written 19:13; the
   commit is 19:22, so it is unverified at HEAD) shows 52 of 59 lines covered, 88.1% against the
   0.85 gate. The tests behind it are meaningful: profiles, flag, data-source coverage rule, unreachable
   database, and no database. The 7 missed lines are the database path of `SyntheticDataGuard`: a
   successful query, the not-migrated states and the password. That path is covered only by
   `persistence`'s `SyntheticDataGuardTest`, which counts towards `persistence`'s bundle, not this one.
3. **The rest of the contract.**
   - Status 200, the exact body, `Content-Type: application/json` and public access through
     `ContractPolicy` and `ContractAuthorizationManager` (matrix `public: true`) are right.
   - CSRF does not apply to a GET without the `CsrfToken` parameter.
   - The contract declares 429, but no limiter covers this anonymous path: `RateLimitFilter` applies
     only to `ApiKeyFilter.PROTECTED`.
   - The 406 response comes from the shared `ProblemHandler.notAcceptable`. It is not tested for this
     endpoint. When `Accept` excludes JSON, the problem body itself may not be writable, so the status
     and body are unverified.
4. **Pre-existing contract and ADR wording.**
   - The `synthetic_data` description at `fraudshield-api.yaml:231` lists seeding and the dev or demo
     profile but omits the database marker, which ADR 0019 point 4 and `SyntheticDataFlag` include.
   - ADR 0019's Consequences cite "the contract test for `getDeploymentEnvironment`". I found none in
     `contracts/tests` or elsewhere.
   - The `m7_authorisation_matrix_*.json` evidence records 404 for this operation because the `admin`
     test application does not include `ingest`. It is not evidence for the assembled API.
5. **The ADR amendment's factual claim holds.** At 4e0a7bc, the only pom that names
   `fraudshield-persistence` is `persistence/pom.xml` itself, and `backend/pom.xml` has no dependency on it.
   So the API ran neither guard, and no `SyntheticDataStatus` bean existed. The amendment's test list
   matches the files. Two things it does not mention: the new fail-closed path breaks the harness (B1),
   and nothing in `docker-compose.yml` starts the API yet.
6. **Guard move and the database tool.**
   - Behaviour is unchanged. The classes are identical apart from the package.
   - `spring.factories` now sits in `BOOT-INF/lib/fraudshield-deployment-guard-*.jar` inside the tool's
     fat jar. `SpringFactoriesLoader` reads nested jars through the launched class loader, as it does
     for Spring Boot's own `spring.factories`.
   - `DriverManager` resolves pgjdbc through the thread context class loader on first use, as before.
   - The built `ingest` jar contains the guard jar and `postgresql-42.7.13.jar`.
   - No test lost coverage in the move:
     - `applicationYamlLeavesSeedingDisabledByDefault` and `seederBeanFactoryChecksAgain` moved to
       `persistence`'s `DemoSeedConfigurationTest`. The first is stronger now: it also asserts the
       property is set to `false`.
     - `SyntheticDataGuardStartupTest` adds the unreachable-database and no-database cases to the new
       module.
     - No stale references to the old package remain.
7. **Dockerfile details (apart from B2).**
   - `-pl ingest -am package -DskipTests` is right. `-DskipTests` still compiles tests, which the
     `decision` and `notify` test-jars need.
   - Paths outside `backend/` that the packaging build reads, all admitted by the ignore file:
     - `contracts/openapi` (ingest and auth resources);
     - `contracts/proto` (decision's protobuf generation, `${maven.multiModuleProjectDirectory}` =
       `/src/backend`, because `.mvn` is in `backend/`);
     - `dataset/generator/params/countries` (analyst resources).
   - `contracts/kafka/schemas` is read only by `decision` tests at run time, so copying it is harmless
     but unneeded.
   - Maven skips a missing resource directory silently. If one of these paths were dropped from the
     ignore file, the build would pass and the API would fail at runtime. The start test (m3) would not
     catch that.
   - `extract --layers --destination` without `--launcher` gives `<layer>/lib/*.jar` and
     `application/fraudshield-ingest-0.1.0-SNAPSHOT.jar`, which matches the four `COPY` lines and the
     `ENTRYPOINT`. `layers.idx` puts the nine FraudShield modules in `snapshot-dependencies`.
   - Runtime user 10001:10001 and ports 8080/8081 match `api.yaml`.
   - Logging goes to the console only: no `logback*.xml` ships in main resources. Tomcat's base
     directory and `hsperfdata` go to `java.io.tmpdir=/tmp`.
   - Docker's `--tmpfs` may default to `noexec` (unverified). If so, Netty, snappy and zstd native
     libraries extracted to `/tmp` would fall back or fail in a fuller start test. The Kubernetes
     `emptyDir` is not `noexec`.
   - The 500 MB limit is unverified, because no image was built. The libraries in the fat jar are about
     117 MB, plus the JRE noble base.
8. **Base images.** Both digests are OCI image indexes (multi-arch: amd64, arm64/v8, ppc64le, riscv64,
   s390x, plus attestation manifests). The registry resolves `21.0.12.1_1-jdk-noble` to
   `sha256:10721595...e964` and `21.0.12.1_1-jre-noble` to `sha256:35501d3f...4c4c`, which matches the
   comments. The workflow step:
   - `set -o pipefail` is needed and present, because a `run:` step without `shell:` runs `bash -e`.
   - A missing or non-executable start test fails the job.
   - Exit 0 or 124 fails the step, and so does exit 125 (no output lines).
   - The start test runs before Trivy. Both gate the job before any push, so the order does not matter.
   - `requirements.yaml` D-21 does not list `EnvironmentController.java` under `implementation`.
