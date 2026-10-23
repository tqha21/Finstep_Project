package com.finstep.common.security;

import com.finstep.identity.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

public class AccountSessionFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    public AccountSessionFilter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM users WHERE id=? AND status='ACTIVE'
                AND email_verified_at IS NOT NULL AND auth_version=?
                """, Integer.class, principal.id(), principal.authVersion());
            if (count == null || count != 1) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
            }
        }
        if (request.getServletPath().startsWith("/api/")) response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request,response);
    }
}
