# Shared download-verify-exec logic for the pinned infrastructure tool launchers (build prompt A.3
# rule 10, G.5). Sourced by the scripts in infrastructure/bin; not executable on its own.
#
# Same trust model as tools/bin/gitleaks: the release artifact is downloaded once into a per-user
# cache and checked against the SHA-256 published in the release's checksums file (pinned below in
# each launcher), and the extracted binary is re-verified against its own pinned SHA-256 on every
# run, so a tampered cache is never executed and nothing is ever taken from PATH.
#
# Usage (from a launcher):
#   . "$(dirname "$0")/lib/pinned.sh"
#   pinned_exec NAME VERSION URL ARTIFACT_SHA256 MEMBER BINARY_SHA256 -- "$@"
# MEMBER is the path of the binary inside a .tar.gz, or "-" when URL is the binary itself.

pinned_exec() {
  name="$1" version="$2" url="$3" artifact_sha="$4" member="$5" binary_sha="$6"
  shift 7 # the six fields and the "--" separator

  if [ "$(uname -s)-$(uname -m)" != "Linux-x86_64" ]; then
    echo "infrastructure/bin/$name: no pinned checksum for $(uname -s)-$(uname -m); add one before use" >&2
    exit 2
  fi

  cache_dir="${XDG_CACHE_HOME:-$HOME/.cache}/fraudshield/${name}-${version}-linux-amd64"
  binary="$cache_dir/$name"

  if [ ! -x "$binary" ]; then
    mkdir -p "$cache_dir"
    download="$cache_dir/download.partial"
    curl -sSLf --retry 3 -o "$download" "$url"
    actual_sha=$(sha256sum "$download" | cut -d' ' -f1)
    if [ "$actual_sha" != "$artifact_sha" ]; then
      rm -f "$download"
      echo "infrastructure/bin/$name: checksum mismatch for $url (got $actual_sha)" >&2
      exit 1
    fi
    if [ "$member" = "-" ]; then
      mv "$download" "$binary"
    else
      tar -xzf "$download" -C "$cache_dir" "$member"
      if [ "$cache_dir/$member" != "$binary" ]; then
        mv "$cache_dir/$member" "$binary"
      fi
      rm -f "$download"
    fi
    chmod 0755 "$binary"
  fi

  actual_binary_sha=$(sha256sum "$binary" | cut -d' ' -f1)
  if [ "$actual_binary_sha" != "$binary_sha" ]; then
    echo "infrastructure/bin/$name: cached binary $binary does not match the pinned SHA-256 (got $actual_binary_sha); deleting it" >&2
    rm -f "$binary"
    exit 1
  fi

  exec "$binary" "$@"
}
