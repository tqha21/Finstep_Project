package com.finstep.reporting;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.UUID;

@Component
public class MonthlyPeriod {
    private final JdbcTemplate jdbc;
    public MonthlyPeriod(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Period(String month,String timezone,OffsetDateTime from,OffsetDateTime to) { }
    public Period forOwner(UUID owner,String month) {
        YearMonth parsed;
        try {
            if(month==null || !month.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw new DateTimeException("month");
            parsed=YearMonth.parse(month);
            if(parsed.getYear()<1) throw new DateTimeException("year");
        } catch(DateTimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tháng cần có định dạng yyyy-MM hợp lệ.");
        }
        var zones=jdbc.query("SELECT timezone FROM users WHERE id=?",(rs,n)->rs.getString(1),owner);
        if(zones.isEmpty())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        ZoneId zone=ZoneId.of(zones.getFirst());
        return new Period(month,zone.getId(),parsed.atDay(1).atStartOfDay(zone).toOffsetDateTime(),
            parsed.plusMonths(1).atDay(1).atStartOfDay(zone).toOffsetDateTime());
    }
}
