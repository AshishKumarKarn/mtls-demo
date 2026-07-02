package com.example.clientdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the mTLS gateway.
 *
 * <p>This is a Spring Boot web application that listens on plain HTTP
 * (port 8080) and acts as a trusted intermediary: browsers/end clients call it
 * without any certificate, and the gateway forwards the request to the
 * protected server over mutual TLS using its own client certificate. See
 * {@link GatewayController} for the exposed endpoints and
 * {@link MtlsRestClientConfig} for the mTLS HTTP client.
 */
@SpringBootApplication
public class ClientDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClientDemoApplication.class, args);
    }
}
