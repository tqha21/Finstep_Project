package com.finstep.common.security;

import com.finstep.identity.TokenHasher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AuthRateLimiter {
    private final JdbcTemplate jdbc;
    public AuthRateLimiter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Separate transaction: rejected requests must still consume their attempt.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allow(String bucket, int limit, int windowSeconds) {
        long now = System.currentTimeMillis() / 1000;
        long window = now / windowSeconds * windowSeconds;
        jdbc.update("DELETE FROM auth_rate_limits WHERE window_start < ?", now - 86400);
        Integer count = jdbc.queryForObject("""
            INSERT INTO auth_rate_limits(bucket_hash,window_start,attempts) VALUES(?,?,1)
            ON CONFLICT(bucket_hash,window_start) DO UPDATE
            SET attempts = least(auth_rate_limits.attempts + 1, 1000000)
            RETURNING attempts
            """, Integer.class, TokenHasher.hash(bucket), window);
        return count != null && count <= limit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ResponseStatusException.class)
    public void require(String bucket, int limit, int seconds) {
        if (!allow(bucket, limit, seconds)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Quá nhiều yêu cầu. Vui lòng thử lại sau.");
        }
    }
}
