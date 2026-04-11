#!/usr/bin/env bash
# Generates EC P-256 test keypair and WireMock response stubs for study-cases E2E.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WIREMOCK_FILES="$SCRIPT_DIR/wiremock/__files"

mkdir -p "$WIREMOCK_FILES"

openssl ecparam -genkey -name prime256v1 -noout -out "$SCRIPT_DIR/private.pem" 2>/dev/null
openssl ec -in "$SCRIPT_DIR/private.pem" -pubout -out "$SCRIPT_DIR/public.pem" 2>/dev/null

PRIVATE_KEY=$(cat "$SCRIPT_DIR/private.pem")
PUBLIC_KEY=$(cat "$SCRIPT_DIR/public.pem")

write_json() {
  local key="$1" algo="$2" outfile="$3"
  local escaped
  escaped=$(printf '%s' "$key" | awk '{printf "%s\\n", $0}')
  printf '{"key":"%s","algorithm":"%s"}\n' "$escaped" "$algo" > "$outfile"
}

write_json "$PRIVATE_KEY" "EC-P256" "$WIREMOCK_FILES/private-key-response.json"
write_json "$PUBLIC_KEY"  "EC-P256" "$WIREMOCK_FILES/public-key-response.json"

echo "Study-cases test keys generated in $SCRIPT_DIR"
