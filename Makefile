# FraudShield developer entry points. Run `make help` for the list.
# Requires: Java 21, uv, Node 24 + pnpm (versions in .tool-versions). Docker with Compose v2 is
# needed only for Docker-dependent suites; without it they print "SKIPPED: ... verified in CI"
# (ADR 0010). Set REQUIRE_DOCKER=1 to make a missing Docker daemon an error instead.

SHELL := /usr/bin/env bash
.SHELLFLAGS := -euo pipefail -c
.DEFAULT_GOAL := help

COMPOSE := docker compose
MVNW := cd backend && ./mvnw -B -ntp
DOCKER_GATE := tools/bin/docker-gate
PNPM := cd frontend && pnpm

.PHONY: help
help: ## List available targets
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  %-18s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# --- Environment -------------------------------------------------------------------------

.PHONY: bootstrap
bootstrap: ## Install locked Python, Node and Maven dependencies
	uv sync --all-packages --locked
	$(PNPM) install --frozen-lockfile
	$(MVNW) -q dependency:go-offline
	uv run pre-commit install --install-hooks

.PHONY: env
env: ## Create .env with random local development credentials (never overwrites)
	@./infrastructure/docker/scripts/generate-dev-env.sh

.PHONY: up
up: env ## Start the core local stack and wait until every service is healthy
	$(COMPOSE) --profile core up -d --wait --wait-timeout 300
	@# The vault's migrations, after the stack is healthy: `run` returns their exit status (ADR 0069).
	$(COMPOSE) --profile core run --rm pii-vault-migrate
	$(COMPOSE) --profile core ps

.PHONY: smoke
smoke: ## Functional smoke test of the running core stack (M0 gate evidence)
	./infrastructure/docker/scripts/smoke-test.sh

.PHONY: seed-demo
seed-demo: ## Migrate the stack database and seed synthetic demo accounts (credentials made locally, ADR 0019)
	$(COMPOSE) --profile core exec -T timescaledb bash /docker-entrypoint-initdb.d/20-fraudshield-roles.sh
	$(MVNW) -q package -pl persistence -am -DskipTests -Djacoco.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true
	uv run --package fraudshield-tools fs-seed-demo

.PHONY: down
down: ## Stop the local stack (volumes are kept)
	$(COMPOSE) --profile full down

.PHONY: ps
ps: ## Show local stack status
	$(COMPOSE) --profile full ps

# --- Quality gates -----------------------------------------------------------------------

.PHONY: lint
lint: ## Lint and format-check all components
	uv run ruff check tools ml contracts dataset
	uv run ruff format --check tools ml contracts dataset
	$(PNPM) lint
	$(PNPM) format:check
	$(MVNW) -q checkstyle:check

.PHONY: typecheck
typecheck: ## Strict type checks (mypy, tsc)
	uv run mypy tools/src tools/tests ml/src ml/tests contracts/src contracts/tests dataset/src dataset/tests
	$(PNPM) typecheck

.PHONY: test-python
test-python: ## Python unit tests with the 90% line-coverage gate (SRS 8.1)
	cd tools && uv run pytest -q
	cd ml && uv run pytest -q
	cd contracts && uv run pytest -q
	cd dataset && uv run pytest -q

.PHONY: test-java
test-java: ## Java build, unit tests, SpotBugs and coverage gate (requires-docker tests need Docker)
	@if docker info >/dev/null 2>&1; then \
	  cd backend && ./mvnw -B -ntp verify; \
	elif [ -n "$${REQUIRE_DOCKER:-}" ]; then \
	  echo "ERROR: JUnit tests tagged requires-docker need Docker; REQUIRE_DOCKER is set" >&2; exit 1; \
	else \
	  echo "SKIPPED: JUnit tests tagged requires-docker require Docker, verified in CI (job: java)"; \
	  cd backend && ./mvnw -B -ntp verify -DexcludedGroups=requires-docker; \
	fi

.PHONY: test-frontend
test-frontend: ## Front-end unit tests with coverage thresholds
	$(PNPM) test:coverage

.PHONY: stack-test
stack-test: ## Start the core stack and run the functional smoke test (Docker)
	$(DOCKER_GATE) stack-test stack -- bash -c 'make up && make smoke'

.PHONY: test
test: test-python test-java test-frontend ## All unit test suites

.PHONY: governance
governance: ## Defect register, traceability and scope checks (D.2, D-47)
	uv run fs-defect-register --check
	uv run fs-traceability-seed --check
	uv run fs-traceability check
	uv run fs-scope-guard
	uv run fs-readme-status
	uv run fs-compose-budget
	uv run fs-contract-baselines --against origin/main
	uv run fs-migration-guard --against origin/main
	uv run fs-dataset provenance --check

.PHONY: traceability
traceability: ## Regenerate the traceability matrix from YAML and test tags
	uv run fs-traceability render

.PHONY: compose-config
compose-config: ## Validate docker-compose.yml without starting containers (needs the docker CLI only)
	$(COMPOSE) --env-file .env.example --profile full config -q

.PHONY: secrets-scan
secrets-scan: ## Gitleaks scan of history and committable working-tree files (pinned binary)
	tools/bin/gitleaks git --log-opts=HEAD --redact --no-banner --exit-code 1 .
	tools/bin/gitleaks-worktree
	uv run python tools/bin/gitleaks-selftest

.PHONY: licences
licences: ## Dependency licence inventory and policy check (ADR 0009)
	uv run fs-licences --output build/licence-inventory.json

.PHONY: ci
ci: lint typecheck test governance compose-config secrets-scan licences stack-test ## Everything CI runs; Docker suites skip visibly without Docker

# --- Later milestones --------------------------------------------------------------------
# These targets are part of the documented interface (build prompt C.5) and are implemented
# by the milestones named below. Until then they fail loudly instead of pretending to work.

.PHONY: bench reproduce verify-all report demo-stream
bench: ## Latency and throughput benchmarks (M3, M5, M6, M10)
	@echo "make bench is delivered in M3 (features), M5 (scoring) and M6 (decisions)" >&2; exit 2
reproduce: ## Dataset -> training -> evaluation -> paper tables (M11)
	@echo "make reproduce is delivered in M11" >&2; exit 2
verify-all: ## Full verification campaign and report (M10)
	@echo "make verify-all is delivered in M10" >&2; exit 2
report: ## Final handover report (M12)
	@echo "make report is delivered in M12" >&2; exit 2
demo-stream: ## Synthetic transaction stream for the demo (M6)
	@echo "make demo-stream is delivered in M6" >&2; exit 2
