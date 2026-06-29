# client-demo (mTLS gateway)

Spring Boot **web application** (plain HTTP, port **8080**) that acts as a
trusted interface to the secured server. Browsers/end clients call it **without
any certificate**; the gateway forwards each request to the server over
**mutual TLS** using its own client certificate.

```
browser --HTTP :8080--> client-demo (gateway) --HTTPS + mTLS :9443--> server-demo
```

## Run (server must be running first)
```bash
mvn clean package -DskipTests
java -jar target/client-demo-1.0.0.jar
```

## Endpoints (plain HTTP — no client cert)
| URL              | What it does                                          |
|------------------|-------------------------------------------------------|
| `/`              | HTML landing page linking everything below.           |
| `/whoami`        | Proxies to server `/api/whoami` over mTLS.            |
| `/ping`          | Proxies to server `/api/ping` over mTLS.             |
| `/server-health` | Proxies to the server's public `/health`.            |
| `/health`        | The gateway's own health (no upstream call).         |

## Try it
```bash
curl http://localhost:8080/whoami
# {"authenticated":true,"clientSubject":"CN=demo-client,...}
```
Or open <http://localhost:8080/> in a browser.

## How it works
- `MtlsRestTemplateConfig` builds a `RestTemplate` on Apache HttpClient 5 with an
  `SSLContext` loaded from `client.p12` (the gateway's identity) and
  `truststore.p12` (the CA used to verify the server).
- `GatewayController` exposes the plain-HTTP endpoints and forwards them to
  `mtls.server-url` over that mutual-TLS client. Upstream failures return HTTP
  502 with a short message.

## Config (`src/main/resources/application.yml`)
- `server.port` — `8080` (plain HTTP)
- `mtls.server-url` — `https://localhost:9443`
- `mtls.key-store` — `client.p12` (gateway identity)
- `mtls.trust-store` — `truststore.p12` (verifies the server)

Store password: `changeit`. See the [top-level README](../README.md) for the
full architecture and certificate-rejection tests.
