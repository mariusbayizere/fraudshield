M9-images | m9/images | /home/marius/fs-wt/m9-images

# Images review 2 (independent)

Range reviewed: `42c13cc..db89bbf`, i.e. `c5e325a`, `4bff519`, `672092b`, `db89bbf`
(`git log --oneline 42c13cc..HEAD`). Reviewed against the contracts and mechanisms each commit relies
on, not only the diff. Nothing was run in Docker. The checks I did run are listed at the end. Anything
I reasoned out but did not run is marked INFERRED.

Counts: 0 BLOCKER, 2 MAJOR, 8 MINOR, 9 NOTE.

## BLOCKER

None found. I read every property the API needs at start-up against `application.yml`,
`FraudShieldProperties`, `AuthProperties`/`AuthAutoConfiguration`, `NotificationWiring`,
`PassphraseKeyProvider`, `SigningKeys` and `ApiHarness.properties`, and each one is supplied with the
right name and format (see "Checked and sound"). This is a static reading only. The API has not been
started from this compose file (MAJOR-2).

## MAJOR

### MAJOR-1: `pii-vault-migrate` has the same initdb race as `fraudshield-migrate`, without the retry, and it now gates the API

- **Where:** `docker-compose.yml:99-100` (pii-vault healthcheck `pg_isready -U postgres -d fraudshield_pii`), `docker-compose.yml:118-135` (pii-vault-migrate: no `FLYWAY_CONNECT_RETRIES`), `docker-compose.yml:351` (the API depends on it completing).
- **What is wrong:** the comment at `docker-compose.yml:71-72` is correct for timescaledb. During initdb the official entrypoint runs the init scripts on a temporary server that listens on the Unix socket only. `pg_isready` without `-h` uses that socket, so the healthcheck can pass before PostgreSQL listens on TCP. The same holds for `pii-vault`: `postgres:16.15`, the same entrypoint, `20-vault-roles.sh` in initdb, and a socket-only healthcheck. Before this commit the vault's one-shot ran only through `make up`, after `--profile core up --wait` had returned, so the window had in practice closed. Now `--profile full up --wait fraudshield-api` from a fresh volume starts `pii-vault-migrate` as soon as the vault is "healthy". A refused TCP connection fails that one-shot, and the API, which needs it completed, never starts.
- **Failure scenario (INFERRED, not reproduced):** the ZAP job (`.github/workflows/security.yml:130-132`) runs `make env` then `docker compose --profile full up -d --wait fraudshield-api` on fresh volumes, so the vault initialises during that `up`. If the first healthcheck that passes falls while `20-vault-roles.sh` is still running, `pii-vault-migrate` exits non-zero, `up --wait` fails and the ZAP job goes red. A developer running `--profile full up` after `down -v` hits the same thing. Its probability depends on how long the vault's init script runs (short), so the failure is intermittent rather than constant.
- **Suggested fix:** best is to make both PostgreSQL healthchecks test TCP (`pg_isready -h 127.0.0.1 ...`). Nothing listens there until initdb has finished, so the race disappears for every dependent, the API included. At the least, give `pii-vault-migrate` the same `FLYWAY_CONNECT_RETRIES` and widen the comment so it covers both one-shots.

### MAJOR-2: no run of `db89bbf` exists; the commit and the start test claim what only a run can show

- **Where:** the `db89bbf` commit message ("proves the API serves requests"), the header of `infrastructure/docker/start-tests/compose-fraudshield-api.sh`, the changed comment in `infrastructure/docker/start-tests/fraudshield-api.sh:11` ("the compose stack's start test does"), and the `requirements_matrix.md` edits.
- **What is wrong:** `gh run list --branch m9/images` shows runs up to `4bff519` and none on `672092b` or `db89bbf`. The commits have not been pushed. The owner's two conditions for this piece are (a) the API cannot start against an unmigrated database and (b) a start test that proves the API serves requests. Both are design intent until the stack job has run the script once. Nothing in this review saw it run. Under the paper-evidence rule, "proves" and "does" state as done something that has not happened yet.
- **Failure scenario:** a document or the hand-over cites "compose start test of the API" as M9 evidence while the job has never executed, or has executed and failed on MAJOR-1, MINOR-1 or something this static review cannot see, such as a Spring wiring surprise in the assembled image.
- **Suggested fix:** push, let the stack job run, and record the run ID and the `docker stats` line the script prints, which is the first measurement of the 1 GiB ASSUMED limit. Until then, word the `fraudshield-api.sh` comment and any notes as "is meant to prove" / "pending first run".

## MINOR

### MINOR-1: step 7 can pass without this run's record, and has no wait

- **Where:** `infrastructure/docker/start-tests/compose-fraudshield-api.sh:124-128`.
- **What is wrong:** the step sums the end offsets of every partition of `fs.transactions.raw` and accepts `>= 1`. The topic lives on the `kafka-data` volume, which `make down` keeps. On any second local run (CI uses `down -v`, so CI is not affected), offsets from earlier runs make the step pass even if this run's transaction never reached Kafka. The step also does not wait. The Kafka drainer runs separately from the Postgres drainer (`DecisionWiring.java:161-174`). Step 6 is most likely answered from Redis (`ResilientPorts.latest`, Redis first; INFERRED), so a 200 there says nothing about the Kafka sink. A slow first metadata fetch could therefore fail the step on CI (INFERRED, low probability: the drainer's idle wait is 2 ms).
- **Suggested fix:** read the offsets before step 4 and require an increase, retrying for up to about 30 s. Better still, consume one record keyed by `$transaction` with `kafka-console-consumer.sh --property print.key=true --max-messages ... --timeout-ms`.

### MINOR-2: the start test can test a stale image

- **Where:** `compose-fraudshield-api.sh:46` (`up -d --wait ...` without `--build`).
- **What is wrong:** `fraudshield-api` has both `build:` and `image: fraudshield-api:local`. Compose builds only when that image is missing. On a developer machine that built it before, the test starts the old image against migrations mounted from the current tree. The check then passes or fails for reasons unrelated to the code under test. CI starts from a clean runner and is not affected.
- **Suggested fix:** use `up -d --build --wait ...`. BuildKit's layer and `/root/.m2` caches keep a rebuild with no changes cheap.

### MINOR-3: the ZAP gate switches on with this commit, without notice, and has never run

- **Where:** `.github/workflows/security.yml:99-117` (`zap-target` greps `^  fraudshield-api:`), `:123` (`timeout-minutes: 30`), `:132` (`up -d --wait` without `--wait-timeout`).
- **What is wrong:** adding the service flips `zap-baseline` from "NOT RUN" (a warning) to a gating job. The commit message does not say so. The job builds the image from scratch: an uncached Maven `-pl ingest -am package` inside BuildKit, plus pulls. It also waits with no bound of its own, inside a 30-minute job. It runs without `seed-demo`. Static reading says the API should start against the migrated, unseeded database (the `dev` profile gives `synthetic_data=true`, and nothing at start needs seeded rows; INFERRED), and MAJOR-1 applies to it.
- **Failure scenario:** the first push of `db89bbf` turns `security` red, either through a timeout during an uncached build or through MAJOR-1, and the cause is not in the commit's own description.
- **Suggested fix:** say in the commit or the hand-over that ZAP is now live. Add `--wait-timeout` and a log dump on failure, as the stack job's script does. Consider 45 minutes, as for the stack job.

### MINOR-4: `STACK_INPUTS` misses the Maven wrapper the image build runs

- **Where:** `.github/workflows/stack.yml:39`.
- **What is wrong:** `backend/Dockerfile` runs `./mvnw` with the wrapper's properties, but `backend/mvnw` and `backend/.mvn/**` do not match the regex (checked with Python against the regex as written). A Maven-version bump would change the image without running the stack job, which is now the only job that builds the API image and starts it in Compose.
- **Suggested fix:** add `backend/mvnw|backend/\.mvn/.*`.

### MINOR-5: `ApiKeyHasherTest` does not prove what its name says (`c5e325a`)

- **Where:** `backend/persistence/src/test/java/.../demo/ApiKeyHasherTest.java:48-57`.
- **What is wrong:** `hmacIsHmacSha256OfTheSecretAndNeedsLongPepper` checks the length (32), determinism, sensitivity to the secret and the pepper bound. All of these would also pass for SHA-256(pepper‖secret), or for an HMAC over the whole key. What matters is that the seeder's hash equals what the API's verifier computes (`ApiKeyAuthenticator.java:301-304`: `Crypto.hmacSha256(pepper, secret US-ASCII)`), and nothing asserts that. `DemoDataSeederTest:140` compares against `ApiKeyHasher.hmac` itself, which is circular. Line 53 (`formatHex(hmac)).doesNotContain(SECRET)`) cannot fail: a hex string never contains the letters `s`, `W`, `X`, `Y`, `Z` that `SECRET` contains.
- **Failure scenario:** someone changes `ApiKeyHasher` to hash the full key or a different encoding, every persistence test stays green, and the seeded demo key is refused by the API with a 401. The compose start test would be the first thing to notice.
- **Suggested fix:** add one known-answer vector (pepper, secret and expected hex from an independent implementation, e.g. Python `hmac`). Drop line 53 or replace it with an assertion that can fail.

### MINOR-6: `CompositeKeysTest` varies one field per key (`c5e325a`)

- **Where:** `backend/persistence/src/test/java/.../schema/CompositeKeysTest.java:35-56`.
- **What is wrong:** each key class is compared against an instance that differs in exactly one field: channel for `RiskThresholdEntity.Key`, version or institution for the others. An `equals` that ignored any of the other fields would still pass. For example, `RiskThresholdEntity.Key` ignoring `institutionId` would pass, and that is the tenant field. The `equals(null)` and `equals("not a key")` lines mostly add coverage. The test is still worth having, since it asserts reflection-set value semantics that `@IdClass` needs.
- **Suggested fix:** run `valueSemantics` once per field, each time varying only that field.

### MINOR-7: the notebook entry is imprecise in three places (`672092b`)

- **Where:** `docs/research/lab_notebook.md:3270-3272` and `:3284-3288`.
- "The guard's tests either exercised its classes directly or started the database tool (`SyntheticDataGuardTest`)": at `b745c83^`, `SyntheticDataGuardTest` also started a `BareApplication` (`@SpringBootConfiguration`) on persistence's test classpath, not only `DatabaseToolApplication`. The conclusion holds, since every application those tests started had persistence's `spring.factories`, but the sentence is narrower than the code.
- "so they passed throughout": this is inferred, not checked against 30 days of CI runs, and the sentence does not say so. Under the evidence rule it needs "(inferred)" or a citation.
- The family paragraph says that in each case "the check was correct where it ran, and the claim was about somewhere it did not run". That fits the scan (2026-10-20) and this guard. It does not fit the cited gitleaks entry (2026-09-24, lines 2670 onward), which its own text describes as a composition defect: self-test, planted secret and scanner each correct, and their composition unsound about 4% of the time. The self-test ran on exactly the values the claim was about.
- **Suggested fix:** name both applications or say "applications on persistence's classpath", mark "passed throughout" as inferred, and either drop the gitleaks analogy or describe it as a neighbouring family (vacuous check), not the same failure.

Everything else in the entry checks out (see "Checked and sound").

### MINOR-8: `make env`, and therefore `make up` for the core profile, now needs `openssl`

- **Where:** `infrastructure/docker/scripts/generate-dev-env.sh:34-37`, `:61`, `:75`. `Makefile` `up: env`.
- **What is wrong:** `make_jwt_key` exits 1 when `openssl` is missing, and it runs on every `make env`, so `make up` (profile core, which never uses the key) now fails on a machine without `openssl`. CI runners and, INFERRED, the devcontainer have it, so the impact is small.
- **Suggested fix:** if `openssl` is missing, warn and skip creating the key instead of failing. The full-profile `up` fails clearly enough without the secret file.

## NOTE

- **N1. `@PreAuthorize("permitAll()")` on `EnvironmentController` does nothing at runtime, and no test checks it.** The URL-level `ContractAuthorizationManager` already admits anonymous callers to `x-public` operations (`SecurityConfiguration.java:105-106`), so the method rule adds nothing. The test that requires `@ContractOperation` plus `@PreAuthorize` on every handler (`AuthorisationMatrixTest`) lives in `backend/admin`, whose context does not contain ingest's controllers. `AnalystAuthorisationTest` is the same for analyst. In `ingest`, `IngestController`, `PublicHealthController`, `ApiDocsController` and `VerificationPageController` are `final` and carry neither annotation. So `final` was given up for documentation and consistency with review 1's m5, not for a mechanism. Two ways to settle it: keep the annotation and add an ingest-level handler test over the assembled API, so the convention is enforced where all the controllers meet; or remove the annotation and restore `final`, matching the other ingest controllers. Either is defensible. As it stands, the annotation is a claim with no test behind it.
- **N2. CI's fail-fast hid the `final` defect at `42c13cc`.** Run 39825015006 stopped at persistence's coverage gate, and every later module, ingest included, was SKIPPED. The "Cannot subclass final class" errors (15, with 35 errors across IngestApiTest, ApiDocsTest, AssembledApplicationTest, ResilienceApiTest and RateLimitApiTest) first appeared on `c5e325a`'s run 39827592788. The answers file's "(c5e325a)" is accurate about the run but not about the commit that introduced the defect (`42c13cc`). Running Maven with `-fae` in the java job would have shown both failures in one run.
- **N3. Some steps of the start test prove less than they appear to.** Step 1 (`/api/v1/health`) is a constant (`PublicHealthController` always answers UP), so it shows only that port 8080 answers. Its comment does not overclaim. Step 3's 401 would also come back for a path that does not exist, because the policy denies by default. Step 4 is what shows the route. Step 6's 404 retry loop is probably never needed, since the state is read from Redis first (INFERRED).
- **N4. 1 GiB with `-XX:MaxRAMPercentage=75`: low risk in the start test, real under sustained load (INFERRED).** With 1 GiB the JVM is not "server class", so SerialGC is chosen and the heap grows with the live set, not up front. The start test's few requests should leave resident memory well under the limit. Under sustained load the heap may grow towards its 768 MiB cap, and with metaspace, code cache, thread stacks and Netty/Kafka direct buffers (a few hundred MiB together) a cgroup OOM kill is plausible, but not guaranteed. `-XX:+ExitOnOutOfMemoryError` does not help against a cgroup kill. The `docker stats` line the script prints is the right first measurement. For anything beyond the start test (ZAP, demos), consider `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60` on the compose service.
- **N5. `fail()` inside `$(...)` runs twice and loses its annotation.** With `set -E`, a failing `curl` inside `request` triggers the ERR trap in the subshell. Its `::error` line goes to the captured stdout and is lost, and its log dump goes to stderr. The parent's assignment then fails and runs `fail` again, so the log is printed twice. No secret is printed: I checked that `$BASH_COMMAND` holds the unexpanded text (`-H "X-API-Key: $api_key"`), and the key is never echoed.
- **N6. Scope correction for this review's brief.** `DemoSeedConfigurationTest` was added by `b745c83`, not `c5e325a`. `c5e325a` added `ApiKeyHasherTest`, `DemoSeedPropertiesTest` and `schema/CompositeKeysTest`.
- **N7. The notebook's lesson is not yet applied.** The entry concludes that a guarantee about every application "needs a test over that set, or a registration that each new application cannot omit". `b745c83` adds a dependency to two poms. Nothing stops a third `@SpringBootApplication` from omitting `deployment-guard`. A small test or `tools` check, for example "every module with a `@SpringBootApplication` under `src/main` depends on `fraudshield-deployment-guard`", would close it.
- **N8. Pepper comparison is textual.** `seed_demo.py:139` compares hex strings, so an `.env` pepper in upper case refuses a file whose pepper is the same bytes in lower case. `make env` writes lower case, so this only affects hand-edited files. Comparing `bytes.fromhex` values would remove the edge.
- **N9. Cost.** The stack job (now 45 minutes, with an uncached image build) runs on every change under `backend/*/src/main/**`. That is intended, but CI minutes will rise noticeably.

## c5e325a and 4bff519

**`c5e325a` (persistence tests for the 0.85 gate): ACCEPT, with MINOR-5 and MINOR-6.** The tests are not coverage padding. `ApiKeyHasherTest` checks the key grammar's boundaries: wrong environment, upper-case key ID, a secret one character short, trailing whitespace. It checks that no message or `toString` carries the secret, and it checks the 32-byte pepper bound. `DemoSeedPropertiesTest` checks that neither settings record prints passwords, the key or the pepper, and that the non-secret field is still shown, so a `toString` returning a constant would fail it. `CompositeKeysTest` checks reflection-set value semantics, which `@IdClass` depends on. Their weaknesses: there is no known-answer HMAC vector, so seeder/API agreement is untested (MINOR-5). One assertion cannot fail (MINOR-5). Only one field per key is varied (MINOR-6). A few lines (`equals(null)`, `equals("not a key")`) are there mostly for coverage. CI on `4bff519` (run 39829865553) met every coverage check.

**`4bff519` (EnvironmentController not final; CT_CONSTRUCTOR_THROW exclusion): ACCEPT, with N1.**
- The fix is correct. `@PreAuthorize` on a class without an interface needs a CGLIB subclass, and CI on `4bff519` is green, ingest's API tests included.
- The exclusion is narrow: one class, one pattern, in `backend/ingest/spotbugs-exclude.xml`, following the analyst module's controllers (`backend/analyst/spotbugs-exclude.xml:15-27`), as the commit says.
- The comment is accurate. The constructor's only throw is `Objects.requireNonNull(status)`. The body is computed from a non-null record, and no security decision depends on the constructor finishing. Spring builds the target once, and the CGLIB proxy instance is created by Objenesis without running the constructor.
- The finalizer-attack concern is not real here. It needs attacker code in the JVM to subclass the controller and capture a half-built instance in `finalize()`. A half-built instance would grant nothing, because the class holds no capability that its constructor's check protects.
- No other class in the branch's change set (`4e0a7bc..HEAD`) has the same hazard. The new deployment-guard classes are `final`, but they are `ApplicationListener`s registered through `spring.factories` and never proxied. `DemoSeedConfiguration` is non-final, with `proxyBeanMethods = false`.
- Whether `final` was given up for anything: at runtime, no (N1). The annotation follows the convention of the modules where `AuthorisationMatrixTest` enforces it, but nothing enforces it in `ingest`. Decide which way to settle it (N1).
- The answers file's new sentence is accurate about the CI run that showed the defect (N2 gives the precise attribution).

## Checked and sound

- **API start-up properties (static).** Every value is supplied under the right relaxed-binding name and format:
  - `FRAUDSHIELD_VAULT_{URL,USERNAME,PASSWORD,KEYPROVIDER,CURRENTKEYID,INDEXKEY}` bind to `fraudshield.vault.*`, and `FRAUDSHIELD_VAULT_MASTERKEYS_DEV` binds to the map key `dev`, which matches `CURRENTKEYID=dev`. The `configured` provider is allowed under the `dev` profile (`NotificationWiring.CONFIGURED_KEY_PROFILES`).
  - The master and index keys are base64 of 32 bytes (`PassphraseKeyProvider` requires exactly 32). Token, secret-box and pepper are hex of 32 bytes (`HexFormat.parseHex`).
  - The JWT key is a PKCS#8 PEM (label `PRIVATE KEY`) from `openssl genpkey` (RSA 2048, at the `SigningKeys` minimum), bound as a `Path`.
  - `FRAUDSHIELD_SMS_{BASEURL,USERNAME,APIKEY}` are set, the console URL and mail-from are non-null, and the pool (20) is above the uncached-read permits (4).
  - Nothing that `ApiHarness.properties` sets is missing, apart from test-only values (ports 0, institutions, deadline).
- **`docker compose --profile full config`** (Compose v5.6.0, scratch copy, throwaway `.env`) resolves. It also resolves for core and full without the PEM file, so CI's `config -q` with `.env.example` is unaffected. Compose budget: full 5504/10240 MiB, core 3968/4096 MiB, 0 errors.
- **Flyway parity.** CLI 12.4.0 matches the Boot 4.1.1 BOM's Flyway 12.4.0 (ADR 0007). The schemas, default schema, `create-schemas=false`, the same files, no placeholders, no Java migrations and no callbacks agree. The remaining defaults (table, baseline, out-of-order, validate) are the same in both. Therefore seed-demo then the one-shot, or the reverse, applies nothing twice and validates the same checksums.
- **timescaledb race and `FLYWAY_CONNECT_RETRIES`.** The reasoning is correct. The healthcheck needs the `mlflow` database (made by `10-…`), so it can pass during `20-fraudshield-roles.sh` while only the socket listens. A refused TCP connection is retried with backoff. The vault is the gap (MAJOR-1).
- **Unmigrated database.** The API also fails at start on an unmigrated database by itself (`ddl-auto: validate`), so `depends_on` is not the only barrier.
- **`up --wait` and one-shots.** All three one-shots are `profiles: [full]` like the API, and the API depends on each with `service_completed_successfully`, as the comment at `pii-vault-migrate` requires. The script then checks each exit code and the topics line.
- **Healthcheck.**
  - The runtime image is `eclipse-temurin` JRE on Ubuntu noble, so bash and `/dev/tcp` are available.
  - `managementSecurity` admits `/actuator/**` exactly when the local port is `management.server.port` (8081). The HTTP/1.0 request closes after the response.
  - Health has no scorer or Kafka indicator. Database, Redis and mail (Mailpit is a healthy dependency) are the components (INFERRED from the starters present).
- **read_only, tmpfs and the spool.**
  - A `tmpfs` `/tmp` with `exec` is needed for native libraries under `-Djava.io.tmpdir=/tmp`.
  - The image creates `/var/lib/fraudshield/spool` owned by 10001, and Docker copies that ownership into the empty named volume on first mount.
  - The secret is bind-mounted with host permissions, and Compose without swarm does not apply ownership to file secrets (INFERRED from Compose behaviour). So 0644 is needed for uid 10001. The 0700 directory still keeps other host users out, and the daemon resolves the mount source. The reasoning in `generate-dev-env.sh` is correct.
  - `backend/Dockerfile.dockerignore` is an allowlist, so `.env` and `.dev-secrets/` never enter the build context.
- **Start-test request.**
  - The body matches `TransactionIngestRequest`: tokens of 24 characters after `tok_`, amount `15000` against `DecimalAmount`, a 4-digit MCC, RWF, a `Z` timestamp, and `additionalProperties` respected.
  - The header is `X-API-Key` (security scheme `apiKeyAuth`). The seeded key is `fsk_dev_` with `ingest:write`, `decisions:read` and `jobs:read`, matching `FRAUDSHIELD_API_KEY_ENVIRONMENT=dev`.
  - Seeder and verifier both compute HMAC-SHA256(pepper bytes from hex, secret US-ASCII) with pepper version 1.
  - `Idempotent-Replayed: true` is what `IngestController` sets.
  - `kafka-get-offsets.sh` prints `topic:partition:offset`, which the `awk -F:` sum reads correctly.
  - No secret reaches the log.
- **`create-topics.sh`.** The `sed` pattern extracts exactly the 13 catalogue topics. Every topic literal in the backend's main code is among them or their `.dlq` topics, and `--if-not-exists` plus the listing check is sound.
- **`seed_demo` pepper change.** The `.env` pepper or the environment pepper is used, with the environment first, the same precedence Compose uses. A new file takes that pepper. An existing file with a different pepper is refused, and the message names the remedy. `make env` appends `FS_API_KEY_PEPPER_HEX` to an older `.env`. `tools/tests/test_seed_demo.py` passes (15 passed, run under mem-gate). The package-wide coverage figure printed by a single-file run does not apply.
- **`stack.yml`.** The new step comes after seed-demo, as the script requires, and its log is uploaded. `down -v` stays in `always()`. The regex covers `backend/*/src/main`, the contracts the Dockerfile copies, `topics.yaml` and the country packs, apart from MINOR-4.
- **Notebook (`672092b`).**
  - ADR 0019 point 5 was accepted on 2026-09-17, and the guards existed from 2026-09-17 (`5ec2c9c`, `02bb017`).
  - `backend/ingest` was created by `246412e` on 2026-09-22, with `FraudShieldApiApplication` as the second `@SpringBootApplication`. No module's pom depended on `fraudshield-persistence` at `246412e` or `b745c83^`.
  - `b745c83` is dated 2026-10-22, which makes 30 days.
  - `getDeploymentEnvironment` and the D-21 row predate the fix (`5ec2c9c`).
  - The cited 2026-10-20 scan entry exists.
  - "inferred from the dependency graph and was not run" is stated where it belongs.
