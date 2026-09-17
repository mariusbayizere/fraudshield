#!/usr/bin/env bash
# Codespaces post-create: install pinned toolchains and locked dependencies, then run `make ci`.
# Every download is checksum-verified. Writes .devcontainer/.post-create-ok on success so the
# CI devcontainer workflow can tell a completed setup from a partial one.
set -Eeuo pipefail

# Emit a GitHub annotation on failure: in the devcontainer CI workflow this is the only part of
# the post-create output that is readable without log access. Harmless in a Codespaces terminal.
trap 'echo "::error title=post-create failed::line ${LINENO}: ${BASH_COMMAND}"' ERR

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

# Keep a copy of all output in the (host-mounted) workspace, so a CI step outside the container
# can report it when post-create fails. Git-ignored.
log_file="$repo_root/.devcontainer/post-create.log"
exec > >(tee "$log_file") 2>&1
echo "post-create started $(date -u +%FT%TZ) as $(id -un) in $repo_root"

# The workspace is mounted from the host and owned by a different user than the container user,
# which git rejects as "dubious ownership"; pre-commit, gitleaks and the traceability checks all
# call git. This writes the container user's git config only, never the host's.
git config --global --add safe.directory "$repo_root"
rm -f .devcontainer/.post-create-ok

UV_VERSION="0.12.15"
UV_SHA256="f97935763c04be3e692460a7aaeaaab8fc3b78fcf8b389da820b38ae7423a638"

mkdir -p "$HOME/.local/bin"
export PATH="$HOME/.local/bin:$PATH"

if ! command -v uv >/dev/null || [[ "$(uv --version | cut -d' ' -f2)" != "$UV_VERSION" ]]; then
  archive=$(mktemp)
  curl -sSLf --retry 3 -o "$archive" \
    "https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/uv-x86_64-unknown-linux-gnu.tar.gz"
  echo "${UV_SHA256}  ${archive}" | sha256sum -c -
  tar -xzf "$archive" -C "$HOME/.local/bin" --strip-components=1 \
    uv-x86_64-unknown-linux-gnu/uv uv-x86_64-unknown-linux-gnu/uvx
  rm -f "$archive"
fi

uv python install "$(cat .python-version)"
uv sync --all-packages --locked

export COREPACK_ENABLE_DOWNLOAD_PROMPT=0
corepack enable --install-directory "$HOME/.local/bin"
(cd frontend && pnpm install --frozen-lockfile)

(cd backend && ./mvnw -B -ntp -q dependency:go-offline)

uv run pre-commit install --install-hooks

# Docker-in-Docker starts with the container; wait briefly so the stack suite is not skipped.
for _ in $(seq 1 30); do
  docker info >/dev/null 2>&1 && break
  sleep 2
done

REQUIRE_DOCKER=1 make ci
touch .devcontainer/.post-create-ok
echo "post-create complete: toolchains installed, make ci passed (including the Docker stack)"
