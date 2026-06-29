package com.example.serverdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the mTLS demo server.
 *
 * <p>The server runs over HTTPS on port 9443 with {@code client-auth: want}
 * (see {@code application.yml}). The public {@code /health} endpoint is reachable
 * without a client certificate, while {@link ClientCertificateFilter} requires a
 * trusted client certificate for everything under {@code /api/**}. A rogue
 * (untrusted) certificate is rejected at the TLS layer regardless of path.
 */
@SpringBootApplication
public class ServerDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServerDemoApplication.class, args);
    }
}
