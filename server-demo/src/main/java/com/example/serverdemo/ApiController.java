package com.example.serverdemo;

import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Protected demo endpoints under {@code /api/**}. By the time any method here
 * executes, two things are guaranteed: the TLS handshake succeeded (so any cert
 * presented chained to the trusted CA) and {@link ClientCertificateFilter} has
 * confirmed a client certificate is actually present. We read that verified
 * certificate from the standard servlet attribute and echo its identity back to
 * prove who was authenticated.
 */
@RestController
public class ApiController {

    /** Liveness check — still requires a valid client cert to reach it. */
    @GetMapping("/api/ping")
    public Map<String, Object> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "pong");
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    /**
     * Returns the identity extracted from the verified client certificate.
     * Reaching this endpoint at all is proof that mTLS authentication passed.
     */
    @GetMapping("/api/whoami")
    public Map<String, Object> whoami(HttpServletRequest request) {
        // Servlet container populates this attribute with the client chain
        // that was validated during the mTLS handshake.
        X509Certificate[] chain =
                (X509Certificate[]) request.getAttribute("jakarta.servlet.request.X509Certificate");

        Map<String, Object> body = new LinkedHashMap<>();
        if (chain != null && chain.length > 0) {
            X509Certificate clientCert = chain[0];
            body.put("authenticated", true);
            body.put("clientSubject", clientCert.getSubjectX500Principal().getName());
            body.put("clientIssuer", clientCert.getIssuerX500Principal().getName());
            body.put("serialNumber", clientCert.getSerialNumber().toString());
            body.put("validUntil", clientCert.getNotAfter().toInstant().toString());
        } else {
            // Unreachable in practice: ClientCertificateFilter rejects /api/**
            // calls that have no certificate. Kept for clarity.
            body.put("authenticated", false);
            body.put("message", "No client certificate was presented.");
        }
        return body;
    }
}
