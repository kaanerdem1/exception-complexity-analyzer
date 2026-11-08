package com.scanner.exception.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * SCANNER_API_KEY tanımlıysa /api altındaki istekler X-API-Key başlığını ister.
 * Swagger arayüzü ve sağlık kontrolleri açık kalır.
 */
@Component
class ApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";

    private final byte[] expected;

    ApiKeyFilter(ServerSettings settings) {
        String key = settings.getApiKey() == null ? "" : settings.getApiKey().trim();
        expected = key.isEmpty() ? null : key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return expected == null || !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        if (given != null && MessageDigest.isEqual(expected, given.trim().getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"title\":\"Yetkisiz\",\"status\":401,\"detail\":\"" + HEADER
                + " başlığı eksik veya hatalı\"}");
    }
}
