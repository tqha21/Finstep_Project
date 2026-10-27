package com.finstep.feedback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finstep.identity.TokenHasher;
import com.finstep.identity.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class FeedbackService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public FeedbackService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public enum Type { BUG, IDEA, OTHER }
    public record Feedback(UUID id, Type type, String message, OffsetDateTime createdAt) { }
    public record Creation(Feedback feedback, boolean replay) { }
    private Feedback map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Feedback(rs.getObject("id",UUID.class),Type.valueOf(rs.getString("type")),rs.getString("message"),rs.getObject("created_at",OffsetDateTime.class));
    }
    public List<Feedback> recent(UUID owner) {
        return jdbc.query("SELECT id,type,message,created_at FROM feedback WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 20",this::map,owner);
    }
    @Transactional
    public Creation create(UserPrincipal owner, String key, Type type, String message) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Idempotency-Key không hợp lệ.");
        String text=message.strip();
        if(text.length()<10) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Phản hồi cần ít nhất 10 ký tự sau khi bỏ khoảng trắng.");
        if(jdbc.query("SELECT id FROM users WHERE id=? AND status='ACTIVE' AND email_verified_at IS NOT NULL AND auth_version=? FOR UPDATE",
            (rs,n)->rs.getObject(1),owner.id(),owner.authVersion()).isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String hash;
        try { hash=TokenHasher.hash(json.writeValueAsString(List.of(type,text))); }
        catch(JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
        var existing=jdbc.query("SELECT id,request_hash FROM feedback WHERE user_id=? AND request_key=?",
            (rs,n)->new Existing(rs.getObject("id",UUID.class),rs.getString("request_hash")),owner.id(),key);
        if(!existing.isEmpty()) {
            if(!existing.getFirst().hash().equals(hash)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Mã gửi đã dùng cho phản hồi khác.");
            return new Creation(get(owner.id(),existing.getFirst().id()),true);
        }
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO feedback(id,user_id,request_key,request_hash,type,message) VALUES(?,?,?,?,?,?)",id,owner.id(),key,hash,type.name(),text);
        return new Creation(get(owner.id(),id),false);
    }
    private record Existing(UUID id,String hash) { }
    private Feedback get(UUID owner,UUID id) {
        return jdbc.query("SELECT id,type,message,created_at FROM feedback WHERE user_id=? AND id=?",this::map,owner,id).getFirst();
    }
}
