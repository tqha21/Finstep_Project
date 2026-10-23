package com.finstep.transaction;

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
public class TransferService {
    private final JdbcTemplate jdbc;
    private final LedgerGuard guard;
    private final IdempotencyStore idempotency;
    public TransferService(JdbcTemplate jdbc,LedgerGuard guard,IdempotencyStore idempotency) {
        this.jdbc=jdbc;this.guard=guard;this.idempotency=idempotency;
    }
    public record Transfer(UUID id,UUID fromWalletId,UUID toWalletId,String amount,OffsetDateTime occurredAt,String note,boolean deleted) { }
    public record Creation(Transfer resource,boolean replay) { }
    private Transfer map(ResultSet rs,int row) throws SQLException {
        return new Transfer(rs.getObject("id",UUID.class),rs.getObject("from_wallet_id",UUID.class),rs.getObject("to_wallet_id",UUID.class),
            rs.getString("amount"),rs.getObject("occurred_at",OffsetDateTime.class),rs.getString("note"),rs.getTimestamp("deleted_at")!=null);
    }
    private Transfer find(UUID owner,UUID id,boolean includeDeleted) {
        return jdbc.query("SELECT * FROM transfers WHERE user_id=? AND id=?"+(includeDeleted?"":" AND deleted_at IS NULL"),this::map,owner,id)
            .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy chuyển ví."));
    }
    public Transfer get(UUID owner,UUID id) { return find(owner,id,false); }
    private void validate(UUID owner,LedgerCommands.Transfer body) {
        if(body.fromWalletId().equals(body.toWalletId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cần hai ví khác nhau.");
        // Consistent wallet order also protects against concurrent archive updates.
        if(body.fromWalletId().compareTo(body.toWalletId())<0) {
            guard.wallet(owner,body.fromWalletId(),body.occurredAt());guard.wallet(owner,body.toWalletId(),body.occurredAt());
        } else {
            guard.wallet(owner,body.toWalletId(),body.occurredAt());guard.wallet(owner,body.fromWalletId(),body.occurredAt());
        }
    }
    @Transactional
    public Creation create(UUID owner,String key,LedgerCommands.Transfer body) {
        guard.lockOwner(owner);
        var canonical=java.util.Arrays.asList(body.fromWalletId(),body.toWalletId(),body.amount(),body.occurredAt().toInstant().toString(),body.note());
        var claim=idempotency.claim(owner,key,"transfer",canonical);
        if(claim.replay()) return new Creation(find(owner,claim.resourceId(),true),true);
        validate(owner,body);
        jdbc.update("INSERT INTO transfers(id,user_id,from_wallet_id,to_wallet_id,amount,occurred_at,note) VALUES(?,?,?,?,?,?,?)",
            claim.resourceId(),owner,body.fromWalletId(),body.toWalletId(),Long.parseLong(body.amount()),body.occurredAt(),body.note());
        return new Creation(get(owner,claim.resourceId()),false);
    }
    @Transactional
    public Transfer replace(UUID owner,UUID id,LedgerCommands.Transfer body) {
        guard.lockOwner(owner);get(owner,id);validate(owner,body);
        jdbc.update("UPDATE transfers SET from_wallet_id=?,to_wallet_id=?,amount=?,occurred_at=?,note=? WHERE user_id=? AND id=? AND deleted_at IS NULL",
            body.fromWalletId(),body.toWalletId(),Long.parseLong(body.amount()),body.occurredAt(),body.note(),owner,id);
        return get(owner,id);
    }
    @Transactional
    public void delete(UUID owner,UUID id) {
        guard.lockOwner(owner);find(owner,id,true);
        jdbc.update("UPDATE transfers SET deleted_at=coalesce(deleted_at,now()) WHERE user_id=? AND id=?",owner,id);
    }
    public HistoryPage<Transfer> history(UUID owner,OffsetDateTime from,OffsetDateTime to,UUID walletId,String cursor,int size) {
        HistoryPage.validate(from,to,size);var position=HistoryPage.decode(cursor);
        StringBuilder sql=new StringBuilder("SELECT * FROM transfers WHERE user_id=? AND deleted_at IS NULL");
        List<Object> args=new ArrayList<>();args.add(owner);
        if(from!=null) {sql.append(" AND occurred_at>=?");args.add(from);}
        if(to!=null) {sql.append(" AND occurred_at<?");args.add(to);}
        if(walletId!=null) {sql.append(" AND (from_wallet_id=? OR to_wallet_id=?)");args.add(walletId);args.add(walletId);}
        if(position!=null) {sql.append(" AND (occurred_at,id)<(?,?)");args.add(java.sql.Timestamp.from(position.at()));args.add(position.id());}
        sql.append(" ORDER BY occurred_at DESC,id DESC LIMIT ?");args.add(size+1);
        var rows=jdbc.query(sql.toString(),this::map,args.toArray());String next=null;
        if(rows.size()>size) {rows=rows.subList(0,size);var last=rows.getLast();next=HistoryPage.encode(last.occurredAt(),last.id());}
        return new HistoryPage<>(rows,next);
    }
}
