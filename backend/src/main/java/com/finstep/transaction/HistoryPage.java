package com.finstep.transaction;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record HistoryPage<T>(List<T> items, String nextCursor) {
    record Position(Instant at,UUID id) { }
    static void validate(OffsetDateTime from,OffsetDateTime to,int size) {
        if(size<1 || size>100 || invalidDate(from) || invalidDate(to) || (from!=null && to!=null && !from.isBefore(to))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Khoảng thời gian hoặc kích thước trang không hợp lệ.");
        }
    }
    private static boolean invalidDate(OffsetDateTime at) { return at!=null && (at.getYear()<1 || at.getYear()>9999); }
    static Position decode(String cursor) {
        if(cursor==null) return null;
        try {
            if(cursor.length()>160) throw new IllegalArgumentException();
            String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=2) throw new IllegalArgumentException();
            Instant at=Instant.parse(parts[0]);
            int year=at.atOffset(java.time.ZoneOffset.UTC).getYear();
            if(year<1 || year>9999) throw new IllegalArgumentException();
            return new Position(at,UUID.fromString(parts[1]));
        } catch(IllegalArgumentException | java.time.DateTimeException badCursor) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cursor không hợp lệ.");
        }
    }
    static String encode(OffsetDateTime at,UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((at.toInstant()+"|"+id).getBytes(StandardCharsets.UTF_8));
    }
}
