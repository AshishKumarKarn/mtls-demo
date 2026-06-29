package com.example.clientdemo;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * Browser-facing gateway.
 *
 * <p>Every endpoint here is plain <b>HTTP</b> on port 8080 — no client
 * certificate is required from the browser/end client. When an endpoint needs
 * data from the protected server, the gateway makes the call itself over
 * <b>mutual TLS</b> using its own client certificate ({@code client.p12}).
 *
 * <pre>
 *   browser --HTTP--> gateway:8080  --HTTPS + mTLS-->  server:9443 /api/**
 * </pre>
 */
@RestController
public class GatewayController {

    private final RestTemplate mtls;
    private final String serverUrl;

    public GatewayController(RestTemplate mtlsRestTemplate,
                             @Value("${mtls.server-url}") String serverUrl) {
        this.mtls = mtlsRestTemplate;
        this.serverUrl = serverUrl;
    }

    /** Human-friendly landing page describing what the gateway exposes. */
    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String index() {
        return """
               <html><body style="font-family:sans-serif">
               <h2>mTLS Gateway (client-demo)</h2>
               <p>Plain HTTP front door. No client certificate needed here —
               the gateway talks to the secured server over mutual TLS for you.</p>
               <ul>
                 <li><a href="/whoami">/whoami</a> — proxy to server <code>/api/whoami</code> (mTLS)</li>
                 <li><a href="/ping">/ping</a> — proxy to server <code>/api/ping</code> (mTLS)</li>
                 <li><a href="/server-health">/server-health</a> — proxy to server <code>/health</code></li>
                 <li><a href="/health">/health</a> — this gateway's own health</li>
               </ul>
               <p>The protected server is at <code>%s</code>.</p>
               </body></html>
               """.formatted(serverUrl);
    }

    /** Proxies to the protected {@code /api/whoami} on the server over mTLS. */
    @GetMapping(value = "/whoami", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> whoami() {
        return proxy("/api/whoami");
    }

    /** Proxies to the protected {@code /api/ping} on the server over mTLS. */
    @GetMapping(value = "/ping", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> ping() {
        return proxy("/api/ping");
    }

    /** Proxies to the server's PUBLIC {@code /health} (still over TLS). */
    @GetMapping(value = "/server-health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> serverHealth() {
        return proxy("/health");
    }

    /** The gateway's own health — no upstream call, always plain HTTP. */
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "client-demo (gateway)");
        body.put("upstream", serverUrl);
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    /**
     * Performs the mutual-TLS call to the server and relays the response.
     * On any failure we return HTTP 502 (Bad Gateway) with a short explanation
     * instead of leaking a stack trace to the browser.
     */
    private ResponseEntity<String> proxy(String path) {
        String url = serverUrl + path;
        try {
            String body = mtls.getForObject(url, String.class);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        } catch (Exception e) {
            String message = e.getClass().getSimpleName() + ": " + e.getMessage();
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"upstream call failed\",\"target\":\"" + url
                            + "\",\"detail\":\"" + message.replace("\"", "'") + "\"}");
        }
    }
}
