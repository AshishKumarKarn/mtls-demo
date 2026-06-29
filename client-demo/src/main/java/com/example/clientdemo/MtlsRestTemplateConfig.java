package com.example.clientdemo;

import java.io.InputStream;
import java.security.KeyStore;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Builds the {@link RestTemplate} the gateway uses to call the mTLS server.
 *
 * <p>The SSLContext is loaded with:
 * <ul>
 *   <li><b>key material</b> — {@code client.p12}, the gateway's own client
 *       certificate, presented to the server during the handshake; and</li>
 *   <li><b>trust material</b> — {@code truststore.p12} (the demo CA), used to
 *       verify the server's certificate.</li>
 * </ul>
 *
 * <p>Browsers never touch any of this: they speak plain HTTP to the gateway,
 * which then performs the mutual-TLS call on their behalf.
 */
@Configuration
public class MtlsRestTemplateConfig {

    @Value("${mtls.trust-store}")
    private Resource trustStore;
    @Value("${mtls.trust-store-password}")
    private String trustStorePassword;

    @Value("${mtls.key-store}")
    private Resource keyStore;
    @Value("${mtls.key-store-password}")
    private String keyStorePassword;

    @Bean
    public RestTemplate mtlsRestTemplate() throws Exception {
        SSLContext sslContext = SSLContextBuilder.create()
                .loadTrustMaterial(load(trustStore, trustStorePassword), null)
                .loadKeyMaterial(load(keyStore, keyStorePassword), keyStorePassword.toCharArray())
                .build();

        SSLConnectionSocketFactory socketFactory = SSLConnectionSocketFactoryBuilder.create()
                .setSslContext(sslContext)
                .build();
        HttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(socketFactory)
                .build();
        HttpClient httpClient = HttpClients.custom().setConnectionManager(cm).build();

        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }

    /** Loads a PKCS12 keystore from a Spring resource. */
    private KeyStore load(Resource resource, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = resource.getInputStream()) {
            ks.load(in, password.toCharArray());
        }
        return ks;
    }
}
