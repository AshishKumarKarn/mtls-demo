package com.example.serverdemo;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUBLIC endpoints — reachable WITHOUT a client certificate.
 *
 * <p>Because the connector runs in {@code client-auth: want} mode, the TLS
 * handshake completes even when the caller presents no certificate, and
 * {@link ClientCertificateFilter} only enforces a certificate for {@code /api/**}.
 * That makes this a browser-friendly health check:
 *
 * <pre>  curl --cacert ca.crt https://localhost:9443/health</pre>
 */
@RestController
public class PublicController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "server-demo");
        body.put("clientCertificateRequired", false);
        body.put("note", "Public endpoint. /api/** still requires a trusted client cert.");
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
