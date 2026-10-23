package com.finstep.budget;

import com.finstep.reporting.MonthlyPeriod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class BudgetService {
    private final JdbcTemplate jdbc;
    private final MonthlyPeriod periods;
    public BudgetService(JdbcTemplate jdbc,MonthlyPeriod periods) { this.jdbc=jdbc;this.periods=periods; }
    public record Limit(@NotNull UUID categoryId,@NotNull @Pattern(regexp="[1-9][0-9]{0,14}") String limitAmount) { }
    public record Replacement(@NotNull @Size(max=100) List<@NotNull @Valid Limit> items) { }
    public record Item(UUID categoryId,String name,boolean archived,String limitAmount,String spent,String remaining,
                       String percentageUsed,String status) { }
    public record Budget(UUID id,MonthlyPeriod.Period period,List<Item> items,String totalLimit,
                         String budgetedSpent,String remaining,String unbudgetedSpent) { }
    @Transactional
    public Budget replace(UUID owner,String month,Replacement body) {
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",UUID.class,owner);
        var period=periods.forOwner(owner,month);
        var seen=new HashSet<UUID>();
        for(var limit:body.items()) {
            if(!seen.add(limit.categoryId()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Danh mục ngân sách bị trùng.");
            var rows=jdbc.query("SELECT type,is_archived FROM categories WHERE user_id=? AND id=? FOR SHARE",
                (rs,n)->new Category(rs.getString("type"),rs.getBoolean("is_archived")),owner,limit.categoryId());
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy danh mục.");
            if(!rows.getFirst().type().equals("EXPENSE"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Ngân sách chỉ dùng danh mục chi tiêu.");
            if(rows.getFirst().archived())throw new ResponseStatusException(HttpStatus.CONFLICT,"Danh mục đã được lưu trữ.");
        }
        UUID budget=jdbc.queryForObject("""
            INSERT INTO budgets(user_id,month) VALUES(?,?) ON CONFLICT(user_id,month)
            DO UPDATE SET month=excluded.month RETURNING id
            """,UUID.class,owner,YearMonth.parse(month).atDay(1));
        jdbc.update("DELETE FROM budget_items WHERE user_id=? AND budget_id=?",owner,budget);
        for(var limit:body.items())jdbc.update("INSERT INTO budget_items(user_id,budget_id,category_id,limit_amount) VALUES(?,?,?,?)",
            owner,budget,limit.categoryId(),Long.parseLong(limit.limitAmount()));
        return project(owner,budget,period);
    }
    private record Category(String type,boolean archived) { }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Budget get(UUID owner,String month) {
        var period=periods.forOwner(owner,month);
        var rows=jdbc.query("SELECT id FROM budgets WHERE user_id=? AND month=?",(rs,n)->rs.getObject("id",UUID.class),owner,YearMonth.parse(month).atDay(1));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Chưa có ngân sách cho tháng này.");
        return project(owner,rows.getFirst(),period);
    }
    private Budget project(UUID owner,UUID id,MonthlyPeriod.Period period) {
        List<Item> items=jdbc.query("""
            SELECT i.category_id,c.name,c.is_archived,i.limit_amount,coalesce(s.spent,0) AS spent
            FROM budget_items i JOIN categories c ON c.id=i.category_id AND c.user_id=i.user_id
            LEFT JOIN (
                SELECT category_id,sum(amount::numeric) AS spent FROM transactions
                WHERE user_id=? AND type='EXPENSE' AND deleted_at IS NULL AND occurred_at>=? AND occurred_at<?
                GROUP BY category_id
            ) s ON s.category_id=i.category_id
            WHERE i.user_id=? AND i.budget_id=? ORDER BY i.category_id
            """,(rs,n)->{
                BigDecimal limit=rs.getBigDecimal("limit_amount"),spent=rs.getBigDecimal("spent");
                String status=spent.compareTo(limit)>0?"EXCEEDED":spent.compareTo(limit)==0?"AT_LIMIT":
                    spent.multiply(BigDecimal.valueOf(100)).compareTo(limit.multiply(BigDecimal.valueOf(80)))>=0?"NEAR_LIMIT":"OK";
                return new Item(rs.getObject("category_id",UUID.class),rs.getString("name"),rs.getBoolean("is_archived"),
                    limit.toPlainString(),spent.toPlainString(),limit.subtract(spent).toPlainString(),
                    spent.multiply(BigDecimal.valueOf(100)).divide(limit,2,RoundingMode.HALF_UP).toPlainString(),status);
            },owner,period.from(),period.to(),owner,id);
        BigDecimal totalLimit=BigDecimal.ZERO,totalSpent=BigDecimal.ZERO;
        for(var item:items) {totalLimit=totalLimit.add(new BigDecimal(item.limitAmount()));totalSpent=totalSpent.add(new BigDecimal(item.spent()));}
        BigDecimal allSpent=jdbc.queryForObject("""
            SELECT coalesce(sum(amount::numeric),0) FROM transactions WHERE user_id=? AND type='EXPENSE'
            AND deleted_at IS NULL AND occurred_at>=? AND occurred_at<?
            """,BigDecimal.class,owner,period.from(),period.to());
        return new Budget(id,period,items,totalLimit.toPlainString(),totalSpent.toPlainString(),
            totalLimit.subtract(totalSpent).toPlainString(),allSpent.subtract(totalSpent).toPlainString());
    }
}
