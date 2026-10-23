package com.finstep.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

public class AuthRateLimitFilter extends OncePerRequestFilter {
    private final AuthRateLimiter limiter;
    public AuthRateLimitFilter(AuthRateLimiter limiter) { this.limiter = limiter; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (request.getMethod().equals("POST") && request.getServletPath().startsWith("/api/v1/auth/")) {
            // Ignore untrusted X-Forwarded-For headers. Configure a trusted ingress before production.
            if (!limiter.allow("ip:" + request.getRemoteAddr(), 30, 60)) {
                response.setStatus(429);
                response.setHeader("Retry-After", "60");
                response.setContentType("application/problem+json");
                response.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
