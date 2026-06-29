# server-demo

Spring Boot HTTPS server (port **9443**) with a **public** health endpoint and
an **mTLS-protected** API.

## Run
```bash
mvn clean package -DskipTests
java -jar target/server-demo-1.0.0.jar
```

## Endpoints
| Method | Path           | Client cert required? | Returns                                          |
|--------|----------------|-----------------------|--------------------------------------------------|
| GET    | `/health`      | **No** (public)       | `{"status":"UP",...}` — browser-friendly check.  |
| GET    | `/api/ping`    | **Yes** (mTLS)        | `{"message":"pong",...}`                         |
| GET    | `/api/whoami`  | **Yes** (mTLS)        | Identity from the verified client certificate.   |

## How it enforces mTLS without locking out the health check
- `application.yml` sets `server.ssl.client-auth: want`, so the TLS handshake
  completes even when no client certificate is sent — that's what lets a plain
  browser reach `/health`.
- `ClientCertificateFilter` then requires a verified client certificate for
  every request under `/api/**`, returning **403** if none is present.
- A rogue (untrusted) certificate still fails the TLS handshake outright,
  regardless of path — `want` relaxes only the "no certificate" case.

## Verify (server running)
```bash
cd ../certs
curl --cacert ca.crt https://localhost:9443/health                                  # 200 (public)
curl --cacert ca.crt https://localhost:9443/api/whoami                              # 403 (no cert)
curl --cacert ca.crt --cert rogue-client.crt --key rogue-client.key \
     https://localhost:9443/api/whoami                                             # TLS reject (56)
curl --cacert ca.crt --cert client.crt --key client.key \
     https://localhost:9443/api/whoami                                             # 200 (valid)
```

## Keystores (`src/main/resources/certs/`)
- `server.p12` — server key + certificate (`key-alias: server`)
- `truststore.p12` — demo CA, used to verify incoming client certificates

Regenerate with `../certs/generate-certs.sh`. Store password: `changeit`.
See the [top-level README](../README.md) for the full architecture.
