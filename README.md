# mTLS Demo — Spring Boot + Java 21

A minimal, fully working demonstration of **mutual TLS (mTLS)** with a
**gateway** in front of a secured server:

| Project        | Role            | Port  | Cert from caller?                                   |
|----------------|-----------------|-------|-----------------------------------------------------|
| `server-demo`  | Secured server  | 9443  | **Yes** for `/api/**`; **No** for public `/health`. |
| `client-demo`  | HTTP gateway    | 8080  | **No** — plain HTTP for browsers/end clients.       |

`client-demo` is a web application that exposes plain **HTTP** to browsers (no
certificate required) and internally calls the secured server over **mutual
TLS** using its own client certificate. The server requires a trusted client
certificate for its `/api/**` endpoints, but also publishes a **public
`/health`** endpoint that any browser can hit without a certificate.

```
  Browser / curl                 client-demo (gateway)            server-demo
  ───────────────  HTTP :8080    ─────────────────────  mTLS :9443 ───────────
  GET /whoami     ───────────▶   proxies with client    ──────────▶ /api/whoami
  (no cert)                      cert (client.p12)       (validates cert)

  Browser / curl                                                    server-demo
  ───────────────  HTTPS :9443 (no client cert) ──────────────────▶ /health
  (public health check)                                             (public)
```

With mTLS, **both** sides prove their identity with X.509 certificates during
the TLS handshake. Here the *gateway* — not the browser — holds the client
certificate, so end users get a normal HTTP API while the sensitive hop runs
over mutual TLS.

---

## Prerequisites

- **Java 21** (`java -version`)
- **Maven 3.8+** (`mvn -version`)
- **OpenSSL** + **keytool** — only needed to regenerate certs (keytool ships with the JDK)

> Certificates/keystores are already generated and committed under each project's
> `src/main/resources/certs/`, so you can run the demo immediately.

---

## Quick start

### 1. (Optional) regenerate all certificates and keystores
```bash
cd certs
./generate-certs.sh
```

### 2. Start the secured server (terminal 1)
```bash
cd server-demo
mvn clean package -DskipTests
java -jar target/server-demo-1.0.0.jar          # https://localhost:9443
```

### 3. Start the gateway (terminal 2)
```bash
cd client-demo
mvn clean package -DskipTests
java -jar target/client-demo-1.0.0.jar          # http://localhost:8080
```

### 4. Use it from a browser or curl — no certificate needed
```bash
curl http://localhost:8080/whoami
# {"authenticated":true,"clientSubject":"CN=demo-client,...","clientIssuer":"CN=mTLS Demo Root CA,..."}
```
Open <http://localhost:8080/> in a browser for a clickable index of all gateway
endpoints.

---

## Endpoints

### Gateway — `http://localhost:8080` (plain HTTP, **no client cert**)
| URL                            | What it does                                                 |
|--------------------------------|--------------------------------------------------------------|
| `/`                            | HTML landing page linking the endpoints below.               |
| `/whoami`                      | Proxies to server `/api/whoami` over **mTLS**.               |
| `/ping`                        | Proxies to server `/api/ping` over **mTLS**.                 |
| `/server-health`               | Proxies to the server's public `/health`.                    |
| `/health`                      | The gateway's own health (no upstream call).                 |

### Server — `https://localhost:9443` (HTTPS)
| URL              | Client cert required? | Notes                                          |
|------------------|-----------------------|------------------------------------------------|
| `/health`        | **No**  (public)      | Browser-friendly health check.                 |
| `/api/ping`      | **Yes** (mTLS)        | Returns `pong`.                                |
| `/api/whoami`    | **Yes** (mTLS)        | Echoes the identity from the verified cert.    |

---

## Verifying certificate enforcement

Run these from the `certs/` directory while **the server** is running. The
`--cacert ca.crt` flag lets curl trust the self-signed demo CA (to verify the
*server*); `--cert`/`--key` present a *client* certificate.

### ✅ Public health check — NO client certificate → ALLOWED
```bash
curl --cacert ca.crt https://localhost:9443/health
# {"status":"UP","service":"server-demo","clientCertificateRequired":false,...}
```

### ❌ Protected API — NO client certificate → REJECTED (HTTP 403)
```bash
curl --cacert ca.crt https://localhost:9443/api/whoami
# {"error":"client certificate required","path":"/api/whoami"}   (HTTP 403)
```

### ❌ Protected API — untrusted ("rogue") certificate → REJECTED (TLS handshake)
```bash
curl --cacert ca.crt --cert rogue-client.crt --key rogue-client.key \
     https://localhost:9443/api/whoami
# curl: (56) ... sslv3 alert certificate unknown
```

### ✅ Protected API — VALID certificate → ALLOWED (HTTP 200)
```bash
curl --cacert ca.crt --cert client.crt --key client.key \
     https://localhost:9443/api/whoami
# {"authenticated":true,"clientSubject":"CN=demo-client,...}
```

### ✅ Through the gateway — plain HTTP, NO certificate → ALLOWED
```bash
curl http://localhost:8080/whoami      # gateway adds the client cert for you
curl http://localhost:8080/ping
```

**Summary of what proves the security model:**
- `/health` works with no certificate (public).
- `/api/**` returns **403** with no certificate (enforced by `ClientCertificateFilter`).
- `/api/**` fails the **TLS handshake** with a rogue certificate (untrusted CA).
- `/api/**` works only with the CA-signed certificate.
- The browser never needs a certificate because the **gateway** owns it.

---

## How it works

### Server: public health + protected API on one port
`server-demo/src/main/resources/application.yml`:
```yaml
server:
  port: 9443
  ssl:
    enabled: true
    key-store: classpath:certs/server.p12       # server identity
    trust-store: classpath:certs/truststore.p12 # CA used to verify clients
    client-auth: want                           # request cert, don't force at TLS layer
```
- `client-auth: want` lets the TLS handshake complete **without** a client cert,
  so the public `/health` endpoint is reachable by any browser.
- `ClientCertificateFilter` then **requires** a verified client certificate for
  every request under `/api/**`, returning **403** otherwise.
- A *rogue* certificate (signed by an untrusted CA) still fails the TLS
  handshake outright — `want` only relaxes the "no certificate" case, not the
  "untrusted certificate" case.

### Gateway: HTTP in, mTLS out
- `MtlsRestTemplateConfig` builds a `RestTemplate` (Apache HttpClient 5) whose
  `SSLContext` is loaded with the gateway's client keystore (`client.p12`) and
  the CA truststore (`truststore.p12`).
- `GatewayController` exposes plain-HTTP endpoints and forwards each one to the
  server over that mutual-TLS `RestTemplate`. On upstream failure it returns
  HTTP **502** with a short message rather than a stack trace.

---

## Certificates

All material is produced by [`certs/generate-certs.sh`](certs/generate-certs.sh):

| File                | Type           | Signed by    | Purpose                                  |
|---------------------|----------------|--------------|------------------------------------------|
| `ca.crt` / `ca.key` | Root CA        | itself       | Trust anchor for the whole demo.         |
| `server.crt/.key`   | Server cert    | demo CA      | Server identity (`CN=localhost`).        |
| `client.crt/.key`   | Client cert    | demo CA      | **Valid** identity used by the gateway.  |
| `rogue-ca.*`        | Rogue Root CA  | itself       | Untrusted CA, simulates an attacker.     |
| `rogue-client.*`    | Client cert    | **rogue CA** | **Untrusted** identity (rejection demo). |

Java keystores (PKCS12, password `changeit`):

| Keystore           | Contains                  | Used by                                  |
|--------------------|---------------------------|------------------------------------------|
| `server.p12`       | server key + cert         | server (its identity)                    |
| `truststore.p12`   | demo CA cert only         | both (verify the peer)                   |
| `client.p12`       | valid client key + cert   | gateway (identity it presents to server) |
| `rogue-client.p12` | rogue client key + cert   | manual curl tests (lives in `certs/`)    |

> **Security note:** these keys are throwaway demo material committed for
> convenience. Never commit private keys or reuse these certificates in real
> systems.

### Inspecting a certificate
```bash
openssl x509 -in certs/client.crt -noout -text
keytool -list -v -keystore certs/client.p12 -storepass changeit
```

---

## Project layout
```
mtls-demo/
├── README.md                  # this file
├── certs/
│   ├── generate-certs.sh       # regenerates all certs + keystores
│   └── *.crt *.key *.p12       # generated material (incl. rogue-client for tests)
├── server-demo/                # secured server (HTTPS :9443)
│   ├── pom.xml
│   ├── README.md
│   └── src/main/
│       ├── java/com/example/serverdemo/
│       │   ├── ServerDemoApplication.java
│       │   ├── ApiController.java            # protected /api/**
│       │   ├── PublicController.java         # public /health
│       │   └── ClientCertificateFilter.java  # enforces cert on /api/**
│       └── resources/
│           ├── application.yml
│           └── certs/          # server.p12, truststore.p12
└── client-demo/                # HTTP gateway (HTTP :8080)
    ├── pom.xml
    ├── README.md
    └── src/main/
        ├── java/com/example/clientdemo/
        │   ├── ClientDemoApplication.java
        │   ├── GatewayController.java        # plain-HTTP endpoints
        │   └── MtlsRestTemplateConfig.java   # mTLS RestTemplate bean
        └── resources/
            ├── application.yml
            └── certs/          # client.p12, truststore.p12
```

---

## Troubleshooting

| Symptom                                            | Cause / fix                                                                |
|----------------------------------------------------|----------------------------------------------------------------------------|
| `Port 9443/8080 was already in use`                | A previous run is still up. `lsof -nP -iTCP:9443 -sTCP:LISTEN`, then kill it. |
| Gateway returns `502 upstream call failed`         | The server isn't running, or keystores are out of sync — restart/regenerate. |
| `curl: (60) SSL certificate problem`               | You omitted `--cacert ca.crt`; curl can't verify the self-signed demo CA.  |
| `/api/**` returns 403 with a valid cert            | Cert not CA-signed or keystores stale — re-run `certs/generate-certs.sh`.  |
| Browser warns about the server cert on `:9443`     | Expected: the demo CA is self-signed. Accept it, or use the gateway on :8080. |
