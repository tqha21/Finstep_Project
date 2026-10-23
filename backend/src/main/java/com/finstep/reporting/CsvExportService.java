package com.finstep.reporting;

import com.finstep.category.CategoryType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class CsvExportService {
    private static final int MAX_ROWS=10000;
    private final JdbcTemplate jdbc;
    public CsvExportService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional(readOnly=true)
    public byte[] export(UUID owner,OffsetDateTime from,OffsetDateTime to,UUID walletId,UUID categoryId,CategoryType type) {
        if(!from.isBefore(to) || from.getYear()<1 || to.getYear()>9999)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Khoảng thời gian xuất không hợp lệ.");
        StringBuilder sql=new StringBuilder("""
            SELECT t.id,t.type,t.amount,t.occurred_at,t.note,w.name AS wallet,c.name AS category
            FROM transactions t JOIN wallets w ON w.id=t.wallet_id AND w.user_id=t.user_id
            JOIN categories c ON c.id=t.category_id AND c.user_id=t.user_id
            WHERE t.user_id=? AND t.deleted_at IS NULL AND t.occurred_at>=? AND t.occurred_at<?
            """);
        List<Object> args=new ArrayList<>();args.add(owner);args.add(from);args.add(to);
        if(walletId!=null) {sql.append(" AND t.wallet_id=?");args.add(walletId);}
        if(categoryId!=null) {sql.append(" AND t.category_id=?");args.add(categoryId);}
        if(type!=null) {sql.append(" AND t.type=?");args.add(type.name());}
        sql.append(" ORDER BY t.occurred_at DESC,t.id DESC LIMIT ?");args.add(MAX_ROWS+1);
        var rows=jdbc.query(sql.toString(),(rs,n)->new String[]{rs.getString("id"),rs.getObject("occurred_at",OffsetDateTime.class).toInstant().toString(),
            rs.getString("type"),rs.getString("amount"),"VND",rs.getString("wallet"),rs.getString("category"),rs.getString("note")},args.toArray());
        if(rows.size()>MAX_ROWS)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Tối đa 10000 giao dịch mỗi lần xuất. Hãy thu hẹp bộ lọc.");
        StringBuilder csv=new StringBuilder("\uFEFF");
        appendRow(csv,new String[]{"id","occurred_at_utc","type","amount","currency","wallet","category","note"});
        for(String[] row:rows)appendRow(csv,row);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
    private void appendRow(StringBuilder csv,String[] row) {
        for(int i=0;i<row.length;i++) {if(i>0)csv.append(',');csv.append(cell(row[i]));}
        csv.append("\r\n");
    }
    private String cell(String raw) {
        String value=raw==null?"":raw;
        int first=0;
        while(first<value.length() && (Character.isWhitespace(value.charAt(first)) || Character.isSpaceChar(value.charAt(first)) || Character.isISOControl(value.charAt(first)) || value.charAt(first)=='\uFEFF'))first++;
        boolean leadingControl=!value.isEmpty() && (Character.isISOControl(value.charAt(0)) || value.charAt(0)=='\uFEFF');
        if(leadingControl || (first<value.length() && "=+-@".indexOf(value.charAt(first))>=0))value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
}
