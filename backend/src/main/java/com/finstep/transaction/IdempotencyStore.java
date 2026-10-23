package com.finstep.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finstep.identity.TokenHasher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Component
public class IdempotencyStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public IdempotencyStore(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    record Claim(UUID resourceId,boolean replay) { }
    // Caller must hold the owner lock and run this inside its mutation transaction.
    Claim claim(UUID owner,String key,String operation,Object canonicalRequest) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Idempotency-Key không hợp lệ.");
        String hash;
        try { hash=TokenHasher.hash(operation+":"+json.writeValueAsString(canonicalRequest)); }
        catch(JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
        var existing=jdbc.query("SELECT resource_id,request_hash FROM idempotency_requests WHERE user_id=? AND key=?",
            (rs,n)->new Existing(rs.getObject("resource_id",UUID.class),rs.getString("request_hash")),owner,key);
        if(!existing.isEmpty()) {
            if(!existing.getFirst().hash().equals(hash))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency-Key đã dùng cho yêu cầu khác.");
            return new Claim(existing.getFirst().id(),true);
        }
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO idempotency_requests(user_id,key,request_hash,resource_id) VALUES(?,?,?,?)",owner,key,hash,id);
        return new Claim(id,false);
    }
    private record Existing(UUID id,String hash) { }
}
