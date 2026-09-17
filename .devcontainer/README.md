# Development container (GitHub Codespaces)

FraudShield's interactive Docker environment (ADR 0010). Docker does not run on the author's
laptop; use this container for the compose stack and for Docker-dependent suites.

- **Machine:** 4 cores / 16 GB RAM / 32 GB storage (`hostRequirements`).
- **Image:** `mcr.microsoft.com/devcontainers/base:3.0.8-ubuntu24.04`, pinned by digest.
- **Features:** Docker-in-Docker 4.1.1 (Compose v2), Java 1.8.3 feature with Temurin
  `21.0.12-tem`, Node 2.1.0 feature with Node 24.21.0 (pnpm comes from Corepack, not the feature).
- **`post-create.sh`:** installs uv 0.12.15 (SHA-256 verified) and Python from `.python-version`,
  syncs the locked uv workspace, installs pnpm dependencies from the lockfile, pre-fetches Maven
  dependencies, installs git hooks, waits for the Docker daemon, then runs `REQUIRE_DOCKER=1 make
  ci` (stack included). On success it writes `.devcontainer/.post-create-ok` (git-ignored), which
  the `devcontainer` CI workflow checks.
- **User and PATH:** runs as `vscode`; `/home/vscode/.local/bin` (uv, uvx, pnpm) is prepended to
  `PATH` through `remoteEnv`.
- **Ports:** 5000 MLflow, 8025 Mailpit UI, 8089 WireMock.

Versions here must match `.tool-versions`; the `devcontainer` workflow rebuilds the container when
either changes. `devcontainer.json` is strict JSON (the `check-json` hook validates it), so
explanations live in this file.
