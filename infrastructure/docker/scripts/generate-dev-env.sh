#!/usr/bin/env sh
# Create .env from .env.example with a fresh random value for every CHANGE_ME placeholder, and the
# API's JWT signing key (.dev-secrets/api-jwt-signing-key.pem).
# Never overwrites an existing .env or key, so local credentials are never silently rotated; it only
# appends variables that .env.example gained since, and creates the key when it is missing.
#
# Placeholders: CHANGE_ME is 24 random bytes in hex (passwords); CHANGE_ME_HEX32 is 32 random bytes
# in hex and CHANGE_ME_BASE64_32 32 random bytes in base64 (keys whose length the API checks).
set -eu

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
example="$root_dir/.env.example"
target="$root_dir/.env"
secrets_dir="$root_dir/.dev-secrets"
jwt_key="$secrets_dir/api-jwt-signing-key.pem"

random_hex() { od -An -tx1 -N"$1" /dev/urandom | tr -d ' \n'; }
random_base64() { head -c "$1" /dev/urandom | base64 | tr -d '\n'; }

# Prints NAME=<value> for a placeholder line, or nothing when the line is not a placeholder.
generated() {
  case "$1" in
    *=CHANGE_ME) printf '%s=%s\n' "${1%=CHANGE_ME}" "$(random_hex 24)" ;;
    *=CHANGE_ME_HEX32) printf '%s=%s\n' "${1%=CHANGE_ME_HEX32}" "$(random_hex 32)" ;;
    *=CHANGE_ME_BASE64_32) printf '%s=%s\n' "${1%=CHANGE_ME_BASE64_32}" "$(random_base64 32)" ;;
  esac
}

# The API reads its RS256 signing key from a PKCS#8 PEM file. The directory is private; the file is
# readable by others because Compose bind-mounts it into a container that runs as user 10001, not
# as the file's owner. Synthetic development stack only (D-21).
make_jwt_key() {
  [ -f "$jwt_key" ] && return 0
  # Only the API (profile full) needs the key; without openssl the core stack still starts.
  command -v openssl >/dev/null 2>&1 || {
    echo "warning: openssl not found; $jwt_key not created, so the API (profile full) cannot start" >&2
    return 0
  }
  mkdir -p "$secrets_dir"
  chmod 700 "$secrets_dir"
  key_tmp=$(mktemp "$secrets_dir/.key.XXXXXX")
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$key_tmp" 2>/dev/null
  chmod 644 "$key_tmp"
  mv "$key_tmp" "$jwt_key"
  echo "wrote the API's JWT signing key to .dev-secrets/api-jwt-signing-key.pem"
}

if [ -f "$target" ]; then
  # Never rotate existing values; only append variables added to .env.example since .env was made.
  added=0
  while IFS= read -r line || [ -n "$line" ]; do
    value=$(generated "$line")
    [ -n "$value" ] || continue
    name=${value%%=*}
    if ! grep -q "^${name}=" "$target"; then
      printf '%s\n' "$value" >> "$target"
      echo "added $name to .env"
      added=1
    fi
  done < "$example"
  [ "$added" = 1 ] || echo ".env already exists; leaving it unchanged"
  make_jwt_key
  exit 0
fi

umask 077
tmp=$(mktemp "$root_dir/.env.XXXXXX")
trap 'rm -f "$tmp"' EXIT
while IFS= read -r line || [ -n "$line" ]; do
  value=$(generated "$line")
  if [ -n "$value" ]; then printf '%s\n' "$value"; else printf '%s\n' "$line"; fi
done < "$example" > "$tmp"
mv "$tmp" "$target"
trap - EXIT
echo "wrote .env with random development credentials (mode 600)"
make_jwt_key
