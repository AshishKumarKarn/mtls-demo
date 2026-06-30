# Concepts — certificate types & how client requests are validated

This document explains every certificate in the demo and exactly how each one
participates in authenticating a request. For the **commands** that create and
sign these certificates, see [`certs/CERTIFICATES.md`](certs/CERTIFICATES.md).

## The trust model in one picture

```
        ca.key  (private — the signing power)
           │ signs
           ▼
   ┌───────┴────────┐
   ca.crt  ◀── self-signed root, the TRUST ANCHOR
   │  signs        │  signs
   ▼               ▼
 server.crt      client.crt        rogue-ca.crt ──signs──▶ rogue-client.crt
 (CN=localhost)  (CN=demo-client)  (separate root,         (looks valid, but
                                    NOT trusted)             chains to wrong root)
```

A certificate is "trusted" only if a chain of signatures leads from it up to a
root that is in the verifier's **truststore**. Both apps trust **only `ca.crt`**,
so anything signed under `rogue-ca` fails.

## The certificate types

| # | Certificate | Signed by | Key fields | Role |
|---|---|---|---|---|
| 1 | **`ca.crt`** / `ca.key` | itself (self-signed) | `CA:TRUE` | **Root CA** — the trust anchor. Its private key signs everyone else; its public cert goes into every truststore. |
| 2 | **`server.crt`** / `server.key` | `ca` | `CN=localhost`, SAN `localhost,127.0.0.1`, EKU `serverAuth` | **Server identity** — proves the server is who the client connects to. |
| 3 | **`client.crt`** / `client.key` | `ca` | `CN=demo-client`, EKU `clientAuth` | **Valid client identity** — what the gateway presents to authenticate itself. |
| 4 | **`rogue-ca.crt`** / `rogue-ca.key` | itself | `CA:TRUE` | A **second, untrusted root** simulating an attacker's own CA. Never added to any truststore. |
| 5 | **`rogue-client.crt`** / `rogue-client.key` | `rogue-ca` | `CN=rogue-client`, EKU `clientAuth` | **Untrusted client identity** — structurally a real cert, but chains to the wrong root, so it is rejected. |

### Why the extensions matter
- **`basicConstraints CA:TRUE/FALSE`** — only a CA cert may sign other certs.
  Leaf certs are `CA:FALSE`, so they cannot impersonate the CA.
- **`extendedKeyUsage`** — `serverAuth` vs `clientAuth` declares *purpose*. The
  server cert may only act as a server; the client certs only as clients. A peer
  can reject a certificate presented for the wrong role.
- **SAN (Subject Alternative Name)** — modern TLS clients verify the hostname
  against the SAN, not the CN. That is why `server.crt` lists `localhost` +
  `127.0.0.1`.

## Keystore vs truststore (how the certs are packaged for Java)

The PEM files above are bundled into PKCS12 stores. The distinction is the whole
point:

| Store | Contains | Question it answers |
|---|---|---|
| **`server.p12`** (keystore) | server key **+ cert** | "What identity do *I* present?" (server) |
| **`client.p12`** (keystore) | client key **+ cert** | "What identity do *I* present?" (gateway) |
| **`truststore.p12`** | **`ca.crt` only** (no private key) | "Whom do *I* trust to vouch for the other side?" |

A **keystore = who I am** (holds a private key). A **truststore = whom I trust**
(public CA cert only). Both apps share the same truststore because both verify
their peer against the same CA.

## How a client request is validated — the mTLS handshake

When the gateway (or `curl`) connects to `https://localhost:9443/api/whoami`,
two-way validation happens *before* any application code runs.

**Step 1 — Client validates the server**
1. Server sends `server.crt`.
2. Client checks: signature traces to a root in its truststore (`ca.crt`)?
   Within validity dates? Hostname matches the SAN (`localhost`)? EKU =
   `serverAuth`?
3. All yes → the server is authenticated and a shared encryption key is derived
   (ECDHE).

**Step 2 — Server validates the client** (the "mutual" part, triggered by `client-auth`)
1. Server asks the client for a certificate.
2. Client sends `client.crt` and signs part of the handshake with its **private**
   `client.key` — proving it actually owns the cert, not just a copy.
3. Server verifies, against its truststore:
   - **Signature / chain** — was `client.crt` signed by `ca` (a trusted root)?
   - **Proof of possession** — does the handshake signature match `client.crt`'s
     public key?
   - **Validity dates** and **EKU = `clientAuth`**.
4. All yes → handshake completes; the validated chain is exposed to the app as
   the servlet attribute `jakarta.servlet.request.X509Certificate`.

## The three outcomes — and exactly where each is decided

| Request | Where it's decided | Result |
|---|---|---|
| **Valid `client.crt`** | Chain validation in the TLS handshake → `ClientCertificateFilter` sees a cert | **200** — `ApiController` reads `CN=demo-client` and echoes it |
| **`rogue-client.crt`** | **TLS handshake** — server cannot chain it to `ca.crt` (its issuer `rogue-ca` isn't trusted) | **Connection fails**: `sslv3 alert certificate unknown` (curl exit 56). App never runs. |
| **No certificate** | Handshake *succeeds* (because `client-auth: want` makes the cert optional at the TLS layer), then **`ClientCertificateFilter`** sees no cert for `/api/**` | **403** `client certificate required` |

The split between the last two is deliberate: `want` lets the public `/health`
endpoint work with no cert, while the filter still enforces a cert on `/api/**`.
A **rogue** cert is rejected regardless of path — `want` only relaxes the
"no cert" case, never the "untrusted cert" case, because an *invalid* cert always
fails the handshake.

## The key security insight

The server never trusts a certificate just because it is well-formed or claims
`CN=demo-client`. Trust comes **only** from the chain of signatures reaching
`ca.crt` in the truststore, combined with **proof of possession** of the matching
private key. That is why the rogue cert — a perfectly valid X.509 certificate
with the right fields — is still rejected: it was signed by the wrong authority,
and the gateway cannot forge `ca`'s signature without `ca.key`.

---

See also: [`README.md`](README.md) (architecture & verification),
[`certs/CERTIFICATES.md`](certs/CERTIFICATES.md) (cert creation/signing commands).
