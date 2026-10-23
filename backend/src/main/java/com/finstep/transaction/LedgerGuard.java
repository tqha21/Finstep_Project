package com.finstep.transaction;

import com.finstep.category.CategoryType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class LedgerGuard {
    private final JdbcTemplate jdbc;
    public LedgerGuard(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    // Serialize mutations of one person's ledger, keeping lock order simple for beta.
    void lockOwner(UUID owner) {
        if(jdbc.query("SELECT id FROM users WHERE id=? FOR UPDATE",(rs,n)->rs.getObject(1),owner).isEmpty())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    void wallet(UUID owner,UUID id,OffsetDateTime occurredAt) {
        if(occurredAt.getYear()<1 || occurredAt.getYear()>9999 || occurredAt.isAfter(OffsetDateTime.now()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Ngày giao dịch không hợp lệ.");
        var rows=jdbc.query("""
            SELECT w.is_archived,o.effective_at FROM wallets w
            LEFT JOIN opening_balances o ON o.wallet_id=w.id AND o.user_id=w.user_id
            WHERE w.user_id=? AND w.id=? FOR SHARE OF w
            """,(rs,n)->new WalletState(rs.getBoolean("is_archived"),rs.getObject("effective_at",OffsetDateTime.class)),owner,id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy ví.");
        var wallet=rows.getFirst();
        if(wallet.archived()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Ví đã được lưu trữ.");
        if(wallet.effectiveAt()!=null && occurredAt.isBefore(wallet.effectiveAt()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Ngày giao dịch trước mốc số dư ban đầu.");
    }
    private record WalletState(boolean archived,OffsetDateTime effectiveAt) { }
    void category(UUID owner,UUID id,CategoryType type) {
        var rows=jdbc.query("SELECT type,is_archived FROM categories WHERE user_id=? AND id=? FOR SHARE",
            (rs,n)->new CategoryState(rs.getString("type"),rs.getBoolean("is_archived")),owner,id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy danh mục.");
        if(rows.getFirst().archived()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Danh mục đã được lưu trữ.");
        if(!rows.getFirst().type().equals(type.name()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Loại danh mục không phù hợp giao dịch.");
    }
    private record CategoryState(String type,boolean archived) { }
}
