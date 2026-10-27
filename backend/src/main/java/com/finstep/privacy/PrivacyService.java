package com.finstep.privacy;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finstep.identity.TokenHasher;
import com.finstep.identity.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;

@Service
public class PrivacyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PasswordEncoder passwords;
    public PrivacyService(JdbcTemplate jdbc, ObjectMapper json, PasswordEncoder passwords) {
        this.jdbc = jdbc; this.json = json; this.passwords = passwords;
    }

    // Synchronous streaming keeps the transaction open until the entire JSON document is written.
    // PostgreSQL cursors bound memory usage; all sections observe one repeatable-read snapshot.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, rollbackFor = IOException.class)
    public void export(UserPrincipal owner, OutputStream output) throws IOException {
        var accounts = jdbc.query("""
            SELECT jsonb_build_object('id',id,'email',email,'fullName',full_name,'currency',currency,
                'timezone',timezone,'emailVerifiedAt',email_verified_at,'createdAt',created_at)::text
            FROM users WHERE id=? AND status='ACTIVE' AND email_verified_at IS NOT NULL AND auth_version=?
            """, (rs, row) -> rs.getString(1), owner.id(), owner.authVersion());
        if (accounts.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try (JsonGenerator generator = json.getFactory().createGenerator(output)) {
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            // A failed query/connection must leave an incomplete document, never a valid partial export.
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT);
            generator.writeStartObject();
            generator.writeNumberField("schemaVersion", 1);
            generator.writeStringField("exportedAt", Instant.now().toString());
            generator.writeFieldName("account"); generator.writeRawValue(accounts.getFirst());
            rows(generator, "wallets", """
                SELECT jsonb_build_object('id',w.id,'name',w.name,'type',w.type,'currency',w.currency,
                    'archived',w.is_archived,'createdAt',w.created_at,'openingBalance',coalesce(o.amount,0)::text,
                    'effectiveAt',o.effective_at,'balance',b.balance::text)
                FROM wallets w LEFT JOIN opening_balances o ON o.user_id=w.user_id AND o.wallet_id=w.id
                JOIN wallet_balances b ON b.user_id=w.user_id AND b.wallet_id=w.id
                WHERE w.user_id=? ORDER BY w.created_at,w.id
                """, owner.id());
            rows(generator, "categories", """
                SELECT jsonb_build_object('id',id,'name',name,'type',type,'archived',is_archived,'createdAt',created_at)
                FROM categories WHERE user_id=? ORDER BY created_at,id
                """, owner.id());
            rows(generator, "transactions", """
                SELECT jsonb_build_object('id',id,'walletId',wallet_id,'categoryId',category_id,'type',type,
                    'amount',amount::text,'note',note,'occurredAt',occurred_at,'createdAt',created_at,'deletedAt',deleted_at)
                FROM transactions WHERE user_id=? ORDER BY occurred_at,id
                """, owner.id());
            rows(generator, "transfers", """
                SELECT jsonb_build_object('id',id,'fromWalletId',from_wallet_id,'toWalletId',to_wallet_id,
                    'amount',amount::text,'note',note,'occurredAt',occurred_at,'createdAt',created_at,'deletedAt',deleted_at)
                FROM transfers WHERE user_id=? ORDER BY occurred_at,id
                """, owner.id());
            rows(generator, "budgets", """
                SELECT jsonb_build_object('id',id,'month',month,'createdAt',created_at)
                FROM budgets WHERE user_id=? ORDER BY month,id
                """, owner.id());
            rows(generator, "budgetItems", """
                SELECT jsonb_build_object('id',id,'budgetId',budget_id,'categoryId',category_id,'limitAmount',limit_amount::text)
                FROM budget_items WHERE user_id=? ORDER BY budget_id,id
                """, owner.id());
            rows(generator, "feedback", """
                SELECT jsonb_build_object('id',id,'type',type,'message',message,'createdAt',created_at)
                FROM feedback WHERE user_id=? ORDER BY created_at,id
                """, owner.id());
            generator.writeEndObject();
        }
    }
    private void rows(JsonGenerator generator, String name, String sql, UUID owner) throws IOException {
        generator.writeArrayFieldStart(name);
        jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setFetchSize(200);
            statement.setObject(1, owner);
            return statement;
        }, (RowCallbackHandler) rs -> {
            try { generator.writeRawValue(rs.getString(1)); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        });
        generator.writeEndArray();
    }

    private record Credentials(String email, String hash) { }
    @Transactional
    public void delete(UserPrincipal owner, String password) {
        // Same owner lock as ledger writes. FK key-share locks serialize wallet/category creation too.
        var accounts = jdbc.query("""
            SELECT email,password_hash FROM users WHERE id=? AND status='ACTIVE'
                AND email_verified_at IS NOT NULL AND auth_version=? FOR UPDATE
            """, (rs, row) -> new Credentials(rs.getString(1), rs.getString(2)), owner.id(), owner.authVersion());
        if (accounts.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var account = accounts.getFirst();
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72 || !passwords.matches(password, account.hash()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu hiện tại không đúng.");
        // Explicit order keeps existing restrictive financial FKs; no global cascade relaxation.
        for (String table : new String[]{"feedback", "budget_items", "budgets", "idempotency_requests", "transactions",
                "transfers", "opening_balances", "wallets", "categories", "identity_tokens"}) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", owner.id());
        }
        jdbc.update("DELETE FROM spring_session WHERE principal_name=?", owner.id().toString());
        jdbc.update("DELETE FROM users WHERE id=?", owner.id());
        for (String bucket : new String[]{"register:" + account.email(), "login:" + account.email(),
                "VERIFY:" + account.email(), "RESET:" + account.email(), "export:" + owner.id(), "delete:" + owner.id(), "password-change:" + owner.id(), "feedback:" + owner.id()}) {
            jdbc.update("DELETE FROM auth_rate_limits WHERE bucket_hash=?", TokenHasher.hash(bucket));
        }
    }
}
