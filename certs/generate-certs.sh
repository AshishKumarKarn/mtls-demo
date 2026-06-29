#!/usr/bin/env bash
#
# generate-certs.sh
# -----------------
# Generates everything needed for the Spring Boot mTLS demo.
#
# PEM material (created with OpenSSL):
#   1. A trusted Certificate Authority (CA)        -> ca.key  / ca.crt
#   2. A server certificate signed by the CA       -> server.key / server.crt
#   3. A VALID client certificate signed by the CA -> client.key / client.crt
#   4. A ROGUE CA + client cert (NOT trusted)      -> rogue-ca.* / rogue-client.*
#
# Java keystores (PKCS12, password "changeit"), built from the PEM material:
#   server.p12        server identity (key + cert)        -> server-demo
#   truststore.p12    the trusted CA cert only            -> server-demo & client-demo
#   client.p12        VALID client identity (key + cert)  -> client-demo (gateway)
#   rogue-client.p12  ROGUE client identity (untrusted)   -> certs/ only (curl tests)
#
# The rogue client cert proves the server rejects identities it does not trust.
# The "no certificate" case is proven by calling the server with no keystore.
#
# Re-running this script regenerates everything from scratch.

set -euo pipefail
cd "$(dirname "$0")"

DAYS=825
SUBJ_BASE="/C=US/ST=Demo/L=Demo/O=mTLS-Demo"
STOREPASS="changeit"

SERVER_RES="../server-demo/src/main/resources/certs"
CLIENT_RES="../client-demo/src/main/resources/certs"

echo "==> Cleaning old material"
rm -f ./*.key ./*.crt ./*.csr ./*.srl ./*.ext ./*.p12

# ---------------------------------------------------------------------------
# 1. Trusted Certificate Authority
# ---------------------------------------------------------------------------
echo "==> [1/4] Creating trusted CA"
openssl genrsa -out ca.key 4096
openssl req -x509 -new -nodes -key ca.key -sha256 -days "$DAYS" \
  -subj "${SUBJ_BASE}/OU=Certificate Authority/CN=mTLS Demo Root CA" \
  -out ca.crt

# ---------------------------------------------------------------------------
# 2. Server certificate (signed by trusted CA), SAN = localhost + 127.0.0.1
# ---------------------------------------------------------------------------
echo "==> [2/4] Creating server certificate (CN=localhost)"
openssl genrsa -out server.key 2048
openssl req -new -key server.key \
  -subj "${SUBJ_BASE}/OU=Server/CN=localhost" -out server.csr
cat > server.ext <<'EOF'
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @alt_names
[alt_names]
DNS.1 = localhost
IP.1  = 127.0.0.1
EOF
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt -days "$DAYS" -sha256 -extfile server.ext

# ---------------------------------------------------------------------------
# 3. VALID client certificate (signed by trusted CA)
# ---------------------------------------------------------------------------
echo "==> [3/4] Creating VALID client certificate (CN=demo-client)"
openssl genrsa -out client.key 2048
openssl req -new -key client.key \
  -subj "${SUBJ_BASE}/OU=Client/CN=demo-client" -out client.csr
cat > client.ext <<'EOF'
basicConstraints = CA:FALSE
keyUsage = digitalSignature
extendedKeyUsage = clientAuth
EOF
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out client.crt -days "$DAYS" -sha256 -extfile client.ext

# ---------------------------------------------------------------------------
# 4. ROGUE CA + ROGUE client certificate (untrusted by the server)
# ---------------------------------------------------------------------------
echo "==> [4/4] Creating ROGUE CA + ROGUE client certificate (untrusted)"
openssl genrsa -out rogue-ca.key 4096
openssl req -x509 -new -nodes -key rogue-ca.key -sha256 -days "$DAYS" \
  -subj "${SUBJ_BASE}/OU=Rogue CA/CN=Rogue Root CA" -out rogue-ca.crt
openssl genrsa -out rogue-client.key 2048
openssl req -new -key rogue-client.key \
  -subj "${SUBJ_BASE}/OU=Rogue Client/CN=rogue-client" -out rogue-client.csr
openssl x509 -req -in rogue-client.csr -CA rogue-ca.crt -CAkey rogue-ca.key \
  -CAcreateserial -out rogue-client.crt -days "$DAYS" -sha256 -extfile client.ext

# ---------------------------------------------------------------------------
# Build PKCS12 keystores + truststore for Spring Boot / Java.
# ---------------------------------------------------------------------------
echo "==> Building PKCS12 keystores (password: ${STOREPASS})"

openssl pkcs12 -export -inkey server.key -in server.crt -certfile ca.crt \
  -name server -out server.p12 -passout pass:"$STOREPASS"

openssl pkcs12 -export -inkey client.key -in client.crt -certfile ca.crt \
  -name client -out client.p12 -passout pass:"$STOREPASS"

openssl pkcs12 -export -inkey rogue-client.key -in rogue-client.crt \
  -name rogue-client -out rogue-client.p12 -passout pass:"$STOREPASS"

# Truststore = the trusted CA certificate only (used to verify the peer).
rm -f truststore.p12
keytool -importcert -noprompt -alias demo-ca -file ca.crt \
  -keystore truststore.p12 -storetype PKCS12 -storepass "$STOREPASS" >/dev/null

# ---------------------------------------------------------------------------
# Distribute into each Spring project's resources/certs directory.
# ---------------------------------------------------------------------------
echo "==> Distributing keystores into Spring projects"
mkdir -p "$SERVER_RES" "$CLIENT_RES"
# Server: its identity + the CA truststore used to verify clients.
cp server.p12 truststore.p12 "$SERVER_RES/"
# Gateway (client-demo): its valid client identity + the CA truststore used to
# verify the server. The rogue keystore stays in certs/ for manual curl tests.
cp client.p12 truststore.p12 "$CLIENT_RES/"

# Clean intermediate files (keep PEM + keystores for inspection).
rm -f ./*.csr ./*.ext

echo
echo "==> Done. Keystores generated:"
ls -1 ./*.p12
echo
echo "Trusted CA SHA-256 fingerprint:"
openssl x509 -in ca.crt -noout -fingerprint -sha256
