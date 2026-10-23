package com.finstep.identity;

import com.finstep.common.security.AuthRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

@Service
public class IdentityService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final MailGateway mail;
    private final AuthRateLimiter limits;
    private final JdbcIndexedSessionRepository sessions;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public record Account(UUID id, String email, String fullName) { }
    private record LoginAccount(UUID id, String hash, boolean usable, int authVersion) { }

    public IdentityService(JdbcTemplate jdbc, PasswordEncoder passwords, MailGateway mail,
                           AuthRateLimiter limits, JdbcIndexedSessionRepository sessions) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.mail = mail;
        this.limits = limits;
        this.sessions = sessions;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    private String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }

    public static void validatePassword(String password) {
        // BCrypt truncates after 72 bytes: reject instead of silently accepting colliding passwords.
        if (password == null || password.length() < 12
            || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu cần ít nhất 12 ký tự và tối đa 72 byte UTF-8.");
        }
    }

    @Transactional
    public void register(String email, String password, String fullName) {
        validatePassword(password);
        String normalized = normalize(email);
        limits.require("register:" + normalized, 5, 900);
        String hash = passwords.encode(password);
        var ids = jdbc.query("""
            INSERT INTO users(email,password_hash,full_name) VALUES(?,?,?)
            ON CONFLICT(email) DO NOTHING RETURNING id
            """, (rs,n) -> rs.getObject("id", UUID.class), normalized, hash, fullName.strip());
        // Generic response for existing addresses, no email/UUID disclosure.
        if (!ids.isEmpty()) issueToken(ids.getFirst(), normalized, "VERIFY");
    }

    public UserPrincipal authenticate(String email, String password) {
        limits.require("login:" + normalize(email), 10, 900);
        var accounts = jdbc.query("""
            SELECT id,password_hash,status,email_verified_at,auth_version FROM users WHERE email=?
            """, (rs,n) -> new LoginAccount(rs.getObject("id",UUID.class),rs.getString("password_hash"),
                rs.getString("status").equals("ACTIVE") && rs.getTimestamp("email_verified_at") != null, rs.getInt("auth_version")), normalize(email));
        LoginAccount account = accounts.isEmpty() ? null : accounts.getFirst();
        boolean withinLimit = password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72;
        boolean matches = passwords.matches(withinLimit ? password : "invalid-length", account == null ? dummyHash : account.hash());
        if (!withinLimit || !matches || account == null || !account.usable()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Thông tin đăng nhập không hợp lệ.");
        }
        return new UserPrincipal(account.id(), account.authVersion());
    }

    @Transactional
    public void requestToken(String email, String purpose) {
        String normalized = normalize(email);
        limits.require(purpose + ":" + normalized, 5, 900);
        var ids = jdbc.query("SELECT id FROM users WHERE email=? AND status='ACTIVE'"
            + (purpose.equals("VERIFY") ? " AND email_verified_at IS NULL" : " AND email_verified_at IS NOT NULL"),
            (rs,n) -> rs.getObject("id",UUID.class), normalized);
        if (!ids.isEmpty()) issueToken(ids.getFirst(), normalized, purpose);
    }

    private void issueToken(UUID user, String email, String purpose) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        int seconds = purpose.equals("VERIFY") ? 86400 : 1800;
        jdbc.update("INSERT INTO identity_tokens(token_hash,user_id,purpose,expires_at) VALUES(?,?,?,now() + ? * interval '1 second')",
            TokenHasher.hash(raw), user, purpose, seconds);
        // Local SMTP by default. SMTP failure rolls back token/account creation.
        // Do not log the token or include it in an HTTP response.
        mail.sendToken(email, purpose, raw);
    }

    @Transactional
    public void consumeToken(String token, String purpose, String newPassword) {
        if (purpose.equals("RESET")) validatePassword(newPassword);
        String tokenHash = TokenHasher.hash(token);
        // Lock the owner first, serializing reset/verify operations for this user.
        var ids = jdbc.query("""
            SELECT u.id FROM users u JOIN identity_tokens t ON t.user_id=u.id
            WHERE t.token_hash=? AND t.purpose=? AND t.expires_at>now()
              AND t.consumed_at IS NULL AND u.status='ACTIVE' FOR UPDATE OF u
            """, (rs,n) -> rs.getObject("id",UUID.class), tokenHash, purpose);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã không hợp lệ hoặc đã hết hạn.");
        UUID user = ids.getFirst();
        int used = jdbc.update("""
            UPDATE identity_tokens SET consumed_at=now()
            WHERE token_hash=? AND consumed_at IS NULL AND expires_at>now()
            """, tokenHash);
        if (used != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã không hợp lệ hoặc đã hết hạn.");
        if (purpose.equals("VERIFY")) {
            jdbc.update("UPDATE users SET email_verified_at=coalesce(email_verified_at,now()) WHERE id=?", user);
        } else {
            jdbc.update("UPDATE users SET password_hash=?,auth_version=auth_version+1 WHERE id=?", passwords.encode(newPassword), user);
            sessions.findByPrincipalName(user.toString()).keySet().forEach(sessions::deleteById);
        }
        // Invalidate sibling tokens too, preventing reuse of an older reset email.
        jdbc.update("UPDATE identity_tokens SET consumed_at=now() WHERE user_id=? AND purpose=? AND consumed_at IS NULL",user,purpose);
    }

    public Account account(UUID id) {
        var accounts = jdbc.query("SELECT id,email,full_name FROM users WHERE id=? AND status='ACTIVE' AND email_verified_at IS NOT NULL",
            (rs,n) -> new Account(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("full_name")),id);
        if (accounts.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return accounts.getFirst();
    }
}
