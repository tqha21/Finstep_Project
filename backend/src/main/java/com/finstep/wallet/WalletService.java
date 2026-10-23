package com.finstep.wallet;

import com.finstep.category.CategoryService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class WalletService {
    private final JdbcTemplate jdbc;
    private final CategoryService categories;
    public WalletService(JdbcTemplate jdbc, CategoryService categories) { this.jdbc=jdbc; this.categories=categories; }
    public record Wallet(UUID id,String name,WalletType type,String currency,boolean archived,
                         String openingBalance,OffsetDateTime effectiveAt,String balance) { }
    private static final String SELECT = """
        SELECT w.*,o.amount AS opening_amount,o.effective_at,b.balance
        FROM wallets w JOIN wallet_balances b ON b.wallet_id=w.id AND b.user_id=w.user_id
        LEFT JOIN opening_balances o ON o.wallet_id=w.id AND o.user_id=w.user_id
        """;
    private Wallet map(ResultSet rs,int row) throws SQLException {
        var opening=rs.getBigDecimal("opening_amount");
        return new Wallet(rs.getObject("id",UUID.class),rs.getString("name"),WalletType.valueOf(rs.getString("type")),
            rs.getString("currency"),rs.getBoolean("is_archived"),opening==null?"0":opening.toPlainString(),
            rs.getObject("effective_at",OffsetDateTime.class),rs.getBigDecimal("balance").toPlainString());
    }
    public List<Wallet> list(UUID owner,boolean includeArchived) {
        return jdbc.query(SELECT+" WHERE w.user_id=? AND (? OR NOT w.is_archived) ORDER BY w.created_at,w.id",this::map,owner,includeArchived);
    }
    public Wallet get(UUID owner,UUID id) {
        return jdbc.query(SELECT+" WHERE w.user_id=? AND w.id=?",this::map,owner,id).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy ví."));
    }
    @Transactional
    public Wallet create(UUID owner,String name,WalletType type,String openingBalance,OffsetDateTime effectiveAt) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO wallets(id,user_id,name,type) VALUES(?,?,?,?)",id,owner,name.strip(),type.name());
        jdbc.update("INSERT INTO opening_balances(wallet_id,user_id,amount,effective_at) VALUES(?,?,?,?)",
            id,owner,Long.parseLong(openingBalance),effectiveAt);
        categories.defaults(owner);
        return get(owner,id);
    }
    @Transactional
    public Wallet update(UUID owner,UUID id,String name,Boolean archived) {
        if (name==null && archived==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cần cung cấp tên hoặc trạng thái lưu trữ.");
        int changed=jdbc.update("UPDATE wallets SET name=coalesce(?,name),is_archived=coalesce(?,is_archived) WHERE user_id=? AND id=?",
            name==null?null:name.strip(),archived,owner,id);
        if (changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy ví.");
        return get(owner,id);
    }
}
