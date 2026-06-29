# Certificate creation & signing — step by step

This document lists **every command** used to build the demo's PKI, in order,
with an explanation of each. All of them are automated in
[`generate-certs.sh`](generate-certs.sh) — this file is the manual walkthrough
so you can see exactly how the self-signed CA and the CA-signed leaf
certificates are produced.

Run everything from the `certs/` directory:
```bash
cd certs
```

Common values used below:
```bash
DAYS=825                                  # validity (~27 months)
SUBJ_BASE="/C=US/ST=Demo/L=Demo/O=mTLS-Demo"
STOREPASS="changeit"                      # PKCS12 keystore password
```

The trust model:

```
              signs                signs
  ca.key ───────────────▶ server.crt   (server identity, CN=localhost)
  ca.crt (self-signed)──▶ client.crt   (VALID client identity, CN=demo-client)

  rogue-ca.crt (self-signed) ──signs──▶ rogue-client.crt   (UNTRUSTED — for the rejection demo)
```

A certificate is "signed by X" when X's private key produces the signature on
it. The server/gateway trust only `ca.crt`, so anything signed by `rogue-ca`
is rejected.

---

## 1. Create the self-signed root CA

The CA is the trust anchor. It signs itself (hence "self-signed") and later
signs the server and client certificates.

```bash
# 1a. Generate the CA private key (4096-bit RSA).
openssl genrsa -out ca.key 4096

# 1b. Create a self-signed CA certificate directly from that key.
#     -x509 makes it a certificate (not a CSR); -nodes = no passphrase on the key.
openssl req -x509 -new -nodes -key ca.key -sha256 -days 825 \
  -subj "/C=US/ST=Demo/L=Demo/O=mTLS-Demo/OU=Certificate Authority/CN=mTLS Demo Root CA" \
  -out ca.crt
```
Output: `ca.key` (keep secret), `ca.crt` (the public trust anchor distributed to
both apps as `truststore.p12`).

---

## 2. Create and sign the server certificate

A leaf certificate is created in three moves: generate a key, create a
**CSR** (Certificate Signing Request), then have the CA **sign** the CSR into a
certificate.

```bash
# 2a. Server private key.
openssl genrsa -out server.key 2048

# 2b. Certificate Signing Request (CSR). CN=localhost matches the hostname
#     clients connect to.
openssl req -new -key server.key \
  -subj "/C=US/ST=Demo/L=Demo/O=mTLS-Demo/OU=Server/CN=localhost" \
  -out server.csr
```

```bash
# 2c. Extensions file. SAN (Subject Alternative Name) is REQUIRED by modern
#     TLS clients for hostname verification; CN alone is ignored.
cat > server.ext <<'EOF'
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @alt_names
[alt_names]
DNS.1 = localhost
IP.1  = 127.0.0.1
EOF
```

```bash
# 2d. SIGN the CSR with the CA -> server.crt.
#     -CAcreateserial creates ca.srl to track issued serial numbers.
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt -days 825 -sha256 -extfile server.ext
```
Output: `server.key` + `server.crt` (server identity, signed by the trusted CA).

---

## 3. Create and sign the VALID client certificate

Same flow as the server, but with `extendedKeyUsage = clientAuth` and no SAN
(client certs are matched by their issuer/subject, not a hostname).

```bash
# 3a. Client private key.
openssl genrsa -out client.key 2048

# 3b. CSR (CN=demo-client is the identity the server will see).
openssl req -new -key client.key \
  -subj "/C=US/ST=Demo/L=Demo/O=mTLS-Demo/OU=Client/CN=demo-client" \
  -out client.csr

# 3c. Client extensions.
cat > client.ext <<'EOF'
basicConstraints = CA:FALSE
keyUsage = digitalSignature
extendedKeyUsage = clientAuth
EOF

# 3d. SIGN the CSR with the SAME trusted CA -> client.crt.
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out client.crt -days 825 -sha256 -extfile client.ext
```
Output: `client.key` + `client.crt` (the identity the gateway presents). Because
it is signed by `ca.crt`, the server accepts it.

---

## 4. Create the ROGUE CA and ROGUE client certificate (for the rejection demo)

To prove the server rejects untrusted certificates, we build a **second,
independent** CA that the server has never heard of, and sign a client cert with
it. Reuses `client.ext` from step 3.

```bash
# 4a. A completely separate self-signed CA.
openssl genrsa -out rogue-ca.key 4096
openssl req -x509 -new -nodes -key rogue-ca.key -sha256 -days 825 \
  -subj "/C=US/ST=Demo/L=Demo/O=mTLS-Demo/OU=Rogue CA/CN=Rogue Root CA" \
  -out rogue-ca.crt

# 4b. A client key + CSR.
openssl genrsa -out rogue-client.key 2048
openssl req -new -key rogue-client.key \
  -subj "/C=US/ST=Demo/L=Demo/O=mTLS-Demo/OU=Rogue Client/CN=rogue-client" \
  -out rogue-client.csr

# 4c. SIGN with the ROGUE CA (NOT the trusted ca.crt) -> rogue-client.crt.
openssl x509 -req -in rogue-client.csr -CA rogue-ca.crt -CAkey rogue-ca.key \
  -CAcreateserial -out rogue-client.crt -days 825 -sha256 -extfile client.ext
```
Output: `rogue-client.key` + `rogue-client.crt`. Presenting this to the server
fails the TLS handshake (`sslv3 alert certificate unknown`).

---

## 5. Package keys + certs into Java keystores (PKCS12)

Java/Spring Boot read **keystores**, not raw PEM files. We bundle each
key+certificate into a PKCS12 keystore, and build a separate **truststore**
holding only the CA certificate.

```bash
# 5a. Identity keystores: private key + its certificate (+ CA in the chain).
openssl pkcs12 -export -inkey server.key -in server.crt -certfile ca.crt \
  -name server -out server.p12 -passout pass:changeit

openssl pkcs12 -export -inkey client.key -in client.crt -certfile ca.crt \
  -name client -out client.p12 -passout pass:changeit

openssl pkcs12 -export -inkey rogue-client.key -in rogue-client.crt \
  -name rogue-client -out rogue-client.p12 -passout pass:changeit
```

```bash
# 5b. Truststore: the CA certificate ONLY. This is what each side uses to
#     verify the other's certificate. Built with the JDK's keytool.
keytool -importcert -noprompt -alias demo-ca -file ca.crt \
  -keystore truststore.p12 -storetype PKCS12 -storepass changeit
```

---

## 6. Distribute keystores into the Spring projects

```bash
# Server gets its identity + the truststore (to verify clients).
cp server.p12 truststore.p12   ../server-demo/src/main/resources/certs/

# Gateway gets its valid client identity + the truststore (to verify the server).
cp client.p12 truststore.p12   ../client-demo/src/main/resources/certs/

# rogue-client.* stays in certs/ and is used only for manual curl tests.
```

---

## Inspecting / verifying the results

```bash
# Read a certificate in full (subject, issuer, SAN, validity, extensions).
openssl x509 -in server.crt -noout -text

# Confirm a leaf is signed by the trusted CA (expect "OK").
openssl verify -CAfile ca.crt server.crt
openssl verify -CAfile ca.crt client.crt

# Confirm the rogue cert is NOT signed by the trusted CA (expect failure).
openssl verify -CAfile ca.crt rogue-client.crt        # -> error: unable to get local issuer

# List the contents of a keystore.
keytool -list -v -keystore client.p12 -storepass changeit

# Fingerprint of the trust anchor.
openssl x509 -in ca.crt -noout -fingerprint -sha256
```

---

## Doing it all at once

Everything above is scripted. To regenerate the entire PKI from scratch:
```bash
./generate-certs.sh
```
The script cleans old material, runs steps 1–6, and prints the CA fingerprint.
