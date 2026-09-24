#!/usr/bin/env sh
# Create .env from .env.example with a fresh random value for every CHANGE_ME placeholder.
# Never overwrites an existing .env, so local credentials are never silently rotated; it only
# appends variables that .env.example gained since.
set -eu

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
example="$root_dir/.env.example"
target="$root_dir/.env"

random_value() { od -An -tx1 -N24 /dev/urandom | tr -d ' \n'; }

if [ -f "$target" ]; then
  # Never rotate existing values; only append variables added to .env.example since .env was made.
  added=0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      *=CHANGE_ME)
        name=${line%=CHANGE_ME}
        if ! grep -q "^${name}=" "$target"; then
          printf '%s=%s\n' "$name" "$(random_value)" >> "$target"
          echo "added $name to .env"
          added=1
        fi
        ;;
    esac
  done < "$example"
  [ "$added" = 1 ] || echo ".env already exists; leaving it unchanged"
  exit 0
fi

umask 077
tmp=$(mktemp "$root_dir/.env.XXXXXX")
trap 'rm -f "$tmp"' EXIT
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    *=CHANGE_ME) printf '%s=%s\n' "${line%=CHANGE_ME}" "$(random_value)" ;;
    *) printf '%s\n' "$line" ;;
  esac
done < "$example" > "$tmp"
mv "$tmp" "$target"
trap - EXIT
echo "wrote .env with random development credentials (mode 600)"
