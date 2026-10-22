# M9 images, review 1: answers

The review is `images-review-1.md` (branch `m9/images` at `581a752`; 2 BLOCKER, 0 MAJOR, 5 MINOR,
8 NOTE). The answers are made in the commit that follows it.

| # | Severity | Finding | Answer |
|---|---|---|---|
| B1 | BLOCKER | Every API test now refuses to start: `TestDatabase` marks its institution as demo data, and the tests run under `test` | Fixed in the API's harness only: `ApiHarness.notDemoData()` clears the marker of its institution. The decision module's shared `TestDatabase` is left as it is, since other modules' tests use it without the guard |
| B2 | BLOCKER | The image build fails at `./mvnw`: no `unzip` in the JDK image | Fixed: the build stage installs `unzip` |
| m1 | MINOR | The endpoint test accepts either value | Fixed: it asserts `{"synthetic_data":false}`, the value the `test` profile and a database without demo data give |
| m2 | MINOR | The start test passes on any `SQLException`, a missing driver included | Fixed: it also expects the PostgreSQL driver's own refusal, "Connection to 127.0.0.1:1 refused" |
| m3 | MINOR | The start test's comment claims more than it shows and cites a compose test that does not exist | Fixed: it says what the three lines prove, and that the compose start test comes with piece 3 (not yet) |
| m4 | MINOR | The guard's connection has no timeouts | Fixed: connect 5 s, login 10 s, socket 10 s (PostgreSQL driver properties); a database that never answers now fails start-up closed instead of stalling it |
| m5 | MINOR | `@PreAuthorize("permitAll()")` missing | Added, as the matrix's `public: true` implies. The first version left the class `final`, which Spring cannot proxy: every API test's context failed on CI (c5e325a); the class is no longer final |
| N1 | NOTE | Stale comments in the moved tests | Fixed |
| N4 | NOTE | The contract's description of `synthetic_data` omits the database marker | Fixed (a description change) |
| N | NOTE | The D-21 row does not list `EnvironmentController.java` | Added |
| N | NOTE | Other notes (429 with no limiter on this path, 406 untested, ADR 0019's missing contract test, `--tmpfs` possibly `noexec`, `contracts/kafka/schemas` unneeded today) | Recorded, not changed; the `noexec` question is checked by the compose start test in piece 3 |
