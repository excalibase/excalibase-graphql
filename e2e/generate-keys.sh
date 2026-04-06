#!/usr/bin/env bash
# Generates EC P-256 test keypair and WireMock response stubs.
# Called before docker-compose up so no secrets are committed to git.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WIREMOCK_FILES="$SCRIPT_DIR/wiremock/__files"

mkdir -p "$WIREMOCK_FILES"

# Generate EC P-256 keypair
openssl ecparam -genkey -name prime256v1 -noout -out "$SCRIPT_DIR/private.pem" 2>/dev/null
openssl ec -in "$SCRIPT_DIR/private.pem" -pubout -out "$SCRIPT_DIR/public.pem" 2>/dev/null

PRIVATE_KEY=$(cat "$SCRIPT_DIR/private.pem")
PUBLIC_KEY=$(cat "$SCRIPT_DIR/public.pem")

# Write WireMock response stubs (keys with \n-escaped newlines for JSON)
write_json() {
  local key="$1" algo="$2" outfile="$3"
  # Replace actual newlines with \n for JSON string
  local escaped
  escaped=$(printf '%s' "$key" | awk '{printf "%s\\n", $0}')
  printf '{"key":"%s","algorithm":"%s"}\n' "$escaped" "$algo" > "$outfile"
}

write_json "$PRIVATE_KEY" "EC-P256" "$WIREMOCK_FILES/private-key-response.json"
write_json "$PUBLIC_KEY"  "EC-P256" "$WIREMOCK_FILES/public-key-response.json"

# Credentials stub (static, no secrets)
cat > "$WIREMOCK_FILES/credentials-response.json" <<'EOF'
{"host":"postgres","port":"5432","username":"auth_admin","password":"authpass","database":"hana"}
EOF

echo "Test keys generated in $SCRIPT_DIR"
