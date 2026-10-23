package com.finstep.reporting;

import com.finstep.category.CategoryType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
public class ReportService {
    private final JdbcTemplate jdbc;
    private final MonthlyPeriod periods;
    public ReportService(JdbcTemplate jdbc,MonthlyPeriod periods) { this.jdbc=jdbc;this.periods=periods; }
    public record CategoryTotal(UUID categoryId,String name,CategoryType type,String amount) { }
    public record DailyTotal(LocalDate date,String income,String expense,String netCashFlow) { }
    public record Summary(MonthlyPeriod.Period period,String totalIncome,String totalExpense,String netCashFlow,
        String currentTotalBalance,List<CategoryTotal> categories,List<DailyTotal> daily) { }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Summary summary(UUID owner,String month) {
        var period=periods.forOwner(owner,month);
        var categories=jdbc.query("""
            SELECT t.category_id,c.name,t.type,sum(t.amount::numeric) AS amount
            FROM transactions t JOIN categories c ON c.id=t.category_id AND c.user_id=t.user_id
            WHERE t.user_id=? AND t.deleted_at IS NULL AND t.occurred_at>=? AND t.occurred_at<?
            GROUP BY t.category_id,c.name,t.type ORDER BY t.type,t.category_id
            """,(rs,n)->new CategoryTotal(rs.getObject("category_id",UUID.class),rs.getString("name"),
                CategoryType.valueOf(rs.getString("type")),rs.getBigDecimal("amount").toPlainString()),owner,period.from(),period.to());
        BigDecimal income=BigDecimal.ZERO,expense=BigDecimal.ZERO;
        for(var category:categories) {
            if(category.type()==CategoryType.INCOME)income=income.add(new BigDecimal(category.amount()));
            else expense=expense.add(new BigDecimal(category.amount()));
        }
        var days=new HashMap<LocalDate,DailyTotal>();
        jdbc.query("""
            SELECT (occurred_at AT TIME ZONE ?)::date AS day,
                coalesce(sum(amount::numeric) FILTER (WHERE type='INCOME'),0) AS income,
                coalesce(sum(amount::numeric) FILTER (WHERE type='EXPENSE'),0) AS expense
            FROM transactions WHERE user_id=? AND deleted_at IS NULL AND occurred_at>=? AND occurred_at<?
            GROUP BY day ORDER BY day
            """,rs->{
                LocalDate day=rs.getObject("day",LocalDate.class);
                BigDecimal i=rs.getBigDecimal("income"),e=rs.getBigDecimal("expense");
                days.put(day,new DailyTotal(day,i.toPlainString(),e.toPlainString(),i.subtract(e).toPlainString()));
            },period.timezone(),owner,period.from(),period.to());
        List<DailyTotal> daily=new ArrayList<>();
        var ym=YearMonth.parse(month);
        for(int day=1;day<=ym.lengthOfMonth();day++) {
            var date=ym.atDay(day);daily.add(days.getOrDefault(date,new DailyTotal(date,"0","0","0")));
        }
        BigDecimal balance=jdbc.queryForObject("SELECT coalesce(sum(balance),0) FROM wallet_balances WHERE user_id=?",BigDecimal.class,owner);
        return new Summary(period,income.toPlainString(),expense.toPlainString(),income.subtract(expense).toPlainString(),
            balance.toPlainString(),categories,daily);
    }
}
