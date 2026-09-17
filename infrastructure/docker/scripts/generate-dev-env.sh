#!/usr/bin/env sh
# Create .env from .env.example with a fresh random value for every CHANGE_ME placeholder.
# Refuses to overwrite an existing .env so local credentials are never silently rotated.
set -eu

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
example="$root_dir/.env.example"
target="$root_dir/.env"

if [ -f "$target" ]; then
  echo ".env already exists; leaving it unchanged"
  exit 0
fi

umask 077
tmp=$(mktemp "$root_dir/.env.XXXXXX")
trap 'rm -f "$tmp"' EXIT
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    *=CHANGE_ME) printf '%s=%s\n' "${line%=CHANGE_ME}" "$(od -An -tx1 -N24 /dev/urandom | tr -d ' \n')" ;;
    *) printf '%s\n' "$line" ;;
  esac
done < "$example" > "$tmp"
mv "$tmp" "$target"
trap - EXIT
echo "wrote .env with random development credentials (mode 600)"
