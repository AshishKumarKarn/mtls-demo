package com.example.serverdemo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces mutual-TLS authentication for protected paths.
 *
 * <p>The connector runs in {@code client-auth: want}, so the TLS handshake
 * succeeds even when no client certificate is presented. That is exactly what
 * we want for the public {@code /health} endpoint. For everything under
 * {@code /api/**}, however, a verified client certificate is mandatory: if the
 * standard servlet attribute that holds the validated client chain is absent,
 * we reject the request with HTTP 403 before it reaches any controller.
 *
 * <p>Note: a rogue/untrusted certificate never reaches this filter — presenting
 * a cert that does not chain to the trusted CA fails the TLS handshake itself.
 */
@Component
public class ClientCertificateFilter extends OncePerRequestFilter {

    private static final String PROTECTED_PREFIX = "/api/";
    private static final String X509_ATTR = "jakarta.servlet.request.X509Certificate";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (request.getRequestURI().startsWith(PROTECTED_PREFIX)) {
            X509Certificate[] chain = (X509Certificate[]) request.getAttribute(X509_ATTR);
            if (chain == null || chain.length == 0) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"error\":\"client certificate required\",\"path\":\""
                                + request.getRequestURI() + "\"}");
                return; // stop the chain — controller never runs
            }
        }
        filterChain.doFilter(request, response);
    }
}
