# WireMock stubs (local fakes for third-party services, D-51)

Mounted read-only at `/home/wiremock` by the `wiremock` service in `docker-compose.yml`.

| Directory | Contents |
|---|---|
| `mappings/` | Request/response stub definitions (`*.json`) |
| `__files/` | Response bodies referenced by mappings |

Stubs are added with each adapter: Africa's Talking SMS and the MNO SIM-swap signal (M6);
Google JWKS, token and revocation endpoints and PagerDuty (M7). Integration tests run against
these fakes; contract tests against real sandboxes run only when credentials are present and are
reported as skipped otherwise.
