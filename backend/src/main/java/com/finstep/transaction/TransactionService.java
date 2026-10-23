package com.finstep.transaction;

import com.finstep.category.CategoryType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TransactionService {
    private final JdbcTemplate jdbc;
    private final LedgerGuard guard;
    private final IdempotencyStore idempotency;
    public TransactionService(JdbcTemplate jdbc,LedgerGuard guard,IdempotencyStore idempotency) {
        this.jdbc=jdbc;this.guard=guard;this.idempotency=idempotency;
    }
    public record Transaction(UUID id,UUID walletId,UUID categoryId,CategoryType type,String amount,
                              OffsetDateTime occurredAt,String note,boolean deleted) { }
    public record Creation(Transaction resource,boolean replay) { }
    private Transaction map(ResultSet rs,int row) throws SQLException {
        return new Transaction(rs.getObject("id",UUID.class),rs.getObject("wallet_id",UUID.class),rs.getObject("category_id",UUID.class),
            CategoryType.valueOf(rs.getString("type")),rs.getString("amount"),rs.getObject("occurred_at",OffsetDateTime.class),
            rs.getString("note"),rs.getTimestamp("deleted_at")!=null);
    }
    private Transaction find(UUID owner,UUID id,boolean includeDeleted) {
        return jdbc.query("SELECT * FROM transactions WHERE user_id=? AND id=?"+(includeDeleted?"":" AND deleted_at IS NULL"),this::map,owner,id)
            .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy giao dịch."));
    }
    public Transaction get(UUID owner,UUID id) { return find(owner,id,false); }
    private void validate(UUID owner,LedgerCommands.Transaction body) {
        guard.wallet(owner,body.walletId(),body.occurredAt());
        guard.category(owner,body.categoryId(),body.type());
    }
    @Transactional
    public Creation create(UUID owner,String key,LedgerCommands.Transaction body) {
        guard.lockOwner(owner);
        // Normalize offsets and amounts so logically identical JSON requests share a hash.
        var canonical=java.util.Arrays.asList(body.walletId(),body.categoryId(),body.type(),body.amount(),body.occurredAt().toInstant().toString(),body.note());
        var claim=idempotency.claim(owner,key,"transaction",canonical);
        if(claim.replay()) return new Creation(find(owner,claim.resourceId(),true),true);
        validate(owner,body);
        jdbc.update("INSERT INTO transactions(id,user_id,wallet_id,category_id,type,amount,occurred_at,note) VALUES(?,?,?,?,?,?,?,?)",
            claim.resourceId(),owner,body.walletId(),body.categoryId(),body.type().name(),Long.parseLong(body.amount()),body.occurredAt(),body.note());
        return new Creation(get(owner,claim.resourceId()),false);
    }
    @Transactional
    public Transaction replace(UUID owner,UUID id,LedgerCommands.Transaction body) {
        guard.lockOwner(owner);get(owner,id);validate(owner,body);
        jdbc.update("UPDATE transactions SET wallet_id=?,category_id=?,type=?,amount=?,occurred_at=?,note=? WHERE user_id=? AND id=? AND deleted_at IS NULL",
            body.walletId(),body.categoryId(),body.type().name(),Long.parseLong(body.amount()),body.occurredAt(),body.note(),owner,id);
        return get(owner,id);
    }
    @Transactional
    public void delete(UUID owner,UUID id) {
        guard.lockOwner(owner);find(owner,id,true);
        jdbc.update("UPDATE transactions SET deleted_at=coalesce(deleted_at,now()) WHERE user_id=? AND id=?",owner,id);
    }
    public HistoryPage<Transaction> history(UUID owner,OffsetDateTime from,OffsetDateTime to,UUID walletId,UUID categoryId,
                                             CategoryType type,String cursor,int size) {
        HistoryPage.validate(from,to,size);var position=HistoryPage.decode(cursor);
        StringBuilder sql=new StringBuilder("SELECT * FROM transactions WHERE user_id=? AND deleted_at IS NULL");
        List<Object> args=new ArrayList<>();args.add(owner);
        if(from!=null) {sql.append(" AND occurred_at>=?");args.add(from);}
        if(to!=null) {sql.append(" AND occurred_at<?");args.add(to);}
        if(walletId!=null) {sql.append(" AND wallet_id=?");args.add(walletId);}
        if(categoryId!=null) {sql.append(" AND category_id=?");args.add(categoryId);}
        if(type!=null) {sql.append(" AND type=?");args.add(type.name());}
        if(position!=null) {sql.append(" AND (occurred_at,id)<(?,?)");args.add(java.sql.Timestamp.from(position.at()));args.add(position.id());}
        sql.append(" ORDER BY occurred_at DESC,id DESC LIMIT ?");args.add(size+1);
        var rows=jdbc.query(sql.toString(),this::map,args.toArray());
        String next=null;
        if(rows.size()>size) {rows=rows.subList(0,size);var last=rows.getLast();next=HistoryPage.encode(last.occurredAt(),last.id());}
        return new HistoryPage<>(rows,next);
    }
}
