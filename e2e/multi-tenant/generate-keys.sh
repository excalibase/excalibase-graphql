#!/usr/bin/env bash
# Generates EC P-256 test keypair and WireMock response stubs for multi-tenant E2E.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WIREMOCK_FILES="$SCRIPT_DIR/wiremock/__files"

mkdir -p "$WIREMOCK_FILES"

# Generate EC P-256 keypair
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

# Generate JWKS response using Node.js (nimbus-compatible format)
# Extracts the EC public key components and builds a JWK Set JSON
node - "$SCRIPT_DIR/public.pem" "$WIREMOCK_FILES/jwks-response.json" <<'EOF'
const fs = require('fs');
const crypto = require('crypto');

const pemFile = process.argv[2];
const outFile = process.argv[3];

const pem = fs.readFileSync(pemFile, 'utf8');
const keyObject = crypto.createPublicKey(pem);
const jwk = keyObject.export({ format: 'jwk' });

const jwkSet = {
  keys: [{
    kty: jwk.kty,
    crv: jwk.crv,
    x: jwk.x,
    y: jwk.y,
    use: 'sig',
    kid: 'test-key'
  }]
};

fs.writeFileSync(outFile, JSON.stringify(jwkSet));
EOF

echo "Multi-tenant test keys generated in $SCRIPT_DIR"
echo "  - private-key-response.json (legacy PEM format)"
echo "  - public-key-response.json  (legacy PEM format)"
echo "  - jwks-response.json        (JWKS format for /.well-known/jwks.json)"
