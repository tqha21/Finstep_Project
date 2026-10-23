package com.finstep;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finstep.budget.BudgetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.session.cookie.secure=false")
class ReportingBudgetIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17");
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired BudgetService budgets;
    static final String PASSWORD="Report-Test-Password-123!";
    static final String HASH=new BCryptPasswordEncoder(4).encode(PASSWORD);
    static final String AT="2026-02-15T00:00:00Z";
    class User {
        final UUID id=UUID.randomUUID();
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        final UUID a,b,expense,income,other;
        User() throws Exception {
            String email=id+"@example.test";
            jdbc.update("INSERT INTO users(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,?,'Report Test',now())",id,email,HASH);
            assertThat(send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),true).statusCode()).isEqualTo(204);
            a=wallet("A");b=wallet("B");
            expense=category("Food","EXPENSE");income=category("Salary","INCOME");other=category("Other","EXPENSE");
        }
        UUID wallet(String name) throws Exception {
            var response=send("POST","/api/v1/wallets",Map.of("name",name,"type","CASH","openingBalance","0","effectiveAt","2025-01-01T00:00:00Z"),true);
            assertThat(response.statusCode()).isEqualTo(201);return UUID.fromString(json.readTree(response.body()).get("id").asText());
        }
        UUID category(String name,String type) {
            return jdbc.queryForObject("INSERT INTO categories(user_id,name,type) VALUES(?,?,?) RETURNING id",UUID.class,id,name,type);
        }
        UUID tx(UUID category,String type,String amount,String date,String note) {
            return jdbc.queryForObject("INSERT INTO transactions(user_id,wallet_id,category_id,type,amount,occurred_at,note) VALUES(?,?,?,?,?,?,?) RETURNING id",
                UUID.class,id,a,category,type,Long.parseLong(amount),OffsetDateTime.parse(date),note);
        }
        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        HttpResponse<String> send(String method,String path,Object body,boolean csrf) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
            if(csrf){var token=json.readTree(get("/api/v1/auth/csrf").body());request.header(token.get("headerName").asText(),token.get("token").asText());}
            return http.send(request.method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        JsonNode report(String month) throws Exception {
            var response=get("/api/v1/reports/summary?month="+month);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body());
        }
        JsonNode budget() throws Exception {
            var response=get("/api/v1/budgets/2026-02");assertThat(response.statusCode()).isEqualTo(200);return json.readTree(response.body());
        }
    }
    Map<String,Object> replacement(UUID category,Object amount) {return Map.of("items",List.of(Map.of("categoryId",category,"limitAmount",amount)));}
    String range() {return "from="+enc("2026-02-01T00:00:00+07:00")+"&to="+enc("2026-03-01T00:00:00+07:00");}
    String enc(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8);}

    @Test void reportsUseLocalMonthAndIncludeArchivedHistoryButNotDeletedOrTransfers() throws Exception {
        User user=new User();User stranger=new User();
        user.tx(user.income,"INCOME","1000","2026-01-31T17:00:00Z",null);
        user.tx(user.expense,"EXPENSE","5","2026-01-31T16:59:59Z",null);
        user.tx(user.expense,"EXPENSE","200","2026-02-28T16:59:59Z",null);
        user.tx(user.expense,"EXPENSE","7","2026-02-28T17:00:00Z",null);
        UUID deleted=user.tx(user.expense,"EXPENSE","10000",AT,null);
        jdbc.update("UPDATE transactions SET deleted_at=now() WHERE id=?",deleted);
        jdbc.update("INSERT INTO transfers(user_id,from_wallet_id,to_wallet_id,amount,occurred_at) VALUES(?,?,?,?,?)",user.id,user.a,user.b,500,OffsetDateTime.parse(AT));
        stranger.tx(stranger.income,"INCOME","999999",AT,"FOREIGN_SECRET");
        jdbc.update("UPDATE wallets SET is_archived=true WHERE id=?",user.a);
        jdbc.update("UPDATE categories SET is_archived=true WHERE id=?",user.expense);
        var report=user.report("2026-02");
        assertThat(report.get("totalIncome").asText()).isEqualTo("1000");
        assertThat(report.get("totalExpense").asText()).isEqualTo("200");
        assertThat(report.get("netCashFlow").asText()).isEqualTo("800");
        assertThat(report.get("currentTotalBalance").asText()).isEqualTo("788");
        assertThat(report.get("daily").size()).isEqualTo(28);
        assertThat(report.get("daily").get(0).get("income").asText()).isEqualTo("1000");
        assertThat(report.get("daily").get(27).get("expense").asText()).isEqualTo("200");
        assertThat(report.get("categories").size()).isEqualTo(2);
        assertThat(report.toString()).doesNotContain("FOREIGN_SECRET",stranger.id.toString());
        var empty=user.report("2026-04");assertThat(empty.get("totalIncome").asText()).isEqualTo("0");assertThat(empty.get("daily").size()).isEqualTo(30);
    }

    @Test void daylightSavingBoundariesAndProfileTimezoneChangesUseActualOffsets() throws Exception {
        User user=new User();
        assertThat(user.send("PATCH","/api/v1/me",Map.of("timezone","America/New_York"),true).statusCode()).isEqualTo(200);
        user.tx(user.expense,"EXPENSE","5","2026-03-01T04:59:59Z",null);
        user.tx(user.income,"INCOME","100","2026-03-01T05:00:00Z",null);
        user.tx(user.expense,"EXPENSE","10","2026-03-08T07:30:00Z",null);
        user.tx(user.expense,"EXPENSE","20","2026-04-01T03:59:59Z",null);
        user.tx(user.expense,"EXPENSE","30","2026-04-01T04:00:00Z",null);
        var report=user.report("2026-03");var period=report.get("period");
        assertThat(Duration.between(OffsetDateTime.parse(period.get("from").asText()),OffsetDateTime.parse(period.get("to").asText())).toHours()).isEqualTo(743);
        assertThat(report.get("totalIncome").asText()).isEqualTo("100");assertThat(report.get("totalExpense").asText()).isEqualTo("30");
        assertThat(report.get("daily").get(7).get("expense").asText()).isEqualTo("10");
        assertThat(user.send("PATCH","/api/v1/me",Map.of("timezone","UTC","fullName"," Updated "),true).statusCode()).isEqualTo(200);
        var utc=user.report("2026-03");assertThat(utc.get("totalExpense").asText()).isEqualTo("15");
        assertThat(json.readTree(user.get("/api/v1/me").body()).get("fullName").asText()).isEqualTo("Updated");
        assertThat(user.send("PATCH","/api/v1/me",Map.of("timezone","Invalid/Timezone"),true).statusCode()).isEqualTo(400);
    }

    @Test void budgetsTrackThresholdsEditsDeletionAndUnbudgetedExpenses() throws Exception {
        User user=new User();UUID expense=user.tx(user.expense,"EXPENSE","800",AT,null);
        user.tx(user.other,"EXPENSE","200",AT,null);user.tx(user.income,"INCOME","9999",AT,null);
        assertThat(user.send("PUT","/api/v1/budgets/2026-02",replacement(user.expense,"1000"),true).statusCode()).isEqualTo(200);
        var budget=user.budget();String id=budget.get("id").asText();
        assertThat(budget.get("items").get(0).get("status").asText()).isEqualTo("NEAR_LIMIT");
        assertThat(budget.get("items").get(0).get("percentageUsed").asText()).isEqualTo("80.00");
        assertThat(budget.get("unbudgetedSpent").asText()).isEqualTo("200");
        Map<String,Object> update=Map.of("walletId",user.a,"categoryId",user.expense,"type","EXPENSE","amount","1100","occurredAt",AT);
        assertThat(user.send("PUT","/api/v1/transactions/"+expense,update,true).statusCode()).isEqualTo(200);
        assertThat(user.budget().get("items").get(0).get("status").asText()).isEqualTo("EXCEEDED");
        assertThat(user.budget().get("remaining").asText()).isEqualTo("-100");
        assertThat(user.send("DELETE","/api/v1/transactions/"+expense,Map.of(),true).statusCode()).isEqualTo(204);
        assertThat(user.budget().get("items").get(0).get("status").asText()).isEqualTo("OK");
        user.tx(user.expense,"EXPENSE","1000",AT,null);
        assertThat(user.budget().get("items").get(0).get("status").asText()).isEqualTo("AT_LIMIT");
        assertThat(user.send("PUT","/api/v1/budgets/2026-02",Map.of("items",List.of()),true).statusCode()).isEqualTo(200);
        var cleared=user.budget();assertThat(cleared.get("id").asText()).isEqualTo(id);assertThat(cleared.get("items").isEmpty()).isTrue();
        assertThat(cleared.get("budgetedSpent").asText()).isEqualTo("0");assertThat(cleared.get("unbudgetedSpent").asText()).isEqualTo("1200");
    }

    @Test void budgetsRejectForeignIncomeDuplicateInvalidAndArchivedCategoriesAtomically() throws Exception {
        User user=new User();User other=new User();
        assertThat(user.get("/api/v1/budgets/2026-02").statusCode()).isEqualTo(404);
        assertThat(user.send("PUT","/api/v1/budgets/2026-02",replacement(user.expense,"1000"),true).statusCode()).isEqualTo(200);
        assertThat(user.send("PUT","/api/v1/budgets/2026-02",replacement(other.expense,"1000"),true).statusCode()).isEqualTo(404);
        assertThat(user.send("PUT","/api/v1/budgets/2026-02",replacement(user.income,"1000"),true).statusCode()).isEqualTo(400);
        var duplicate=Map.of("items",List.of(Map.of("categoryId",user.expense,"limitAmount","10"),Map.of("categoryId",user.expense,"limitAmount","20")));
        assertThat(user.send("PUT","/api/v1/budgets/2026-02",duplicate,true).statusCode()).isEqualTo(400);
        for(Object amount:List.of("0","-1","1.5",1000))assertThat(user.send("PUT","/api/v1/budgets/2026-02",replacement(user.expense,amount),true).statusCode()).isEqualTo(400);
        jdbc.update("UPDATE categories SET is_archived=true WHERE id=?",user.expense);
        assertThat(user.send("PUT","/api/v1/budgets/2026-02",replacement(user.expense,"2000"),true).statusCode()).isEqualTo(409);
        assertThat(user.budget().get("totalLimit").asText()).isEqualTo("1000");assertThat(user.budget().get("items").get(0).get("archived").asBoolean()).isTrue();
        assertThat(other.get("/api/v1/budgets/2026-02").statusCode()).isEqualTo(404);
    }

    @Test void concurrentBudgetReplacementsKeepOneCompleteVersion() throws Exception {
        User user=new User();CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{start.await();return budgets.replace(user.id,"2026-02",new BudgetService.Replacement(List.of(new BudgetService.Limit(user.expense,"100"))));});
            var second=pool.submit(()->{start.await();return budgets.replace(user.id,"2026-02",new BudgetService.Replacement(List.of(new BudgetService.Limit(user.other,"200"))));});
            start.countDown();var a=first.get(20,TimeUnit.SECONDS);var b=second.get(20,TimeUnit.SECONDS);
            assertThat(a.id()).isEqualTo(b.id());
        }
        var finalBudget=user.budget();assertThat(finalBudget.get("items").size()).isEqualTo(1);
        assertThat(finalBudget.get("totalLimit").asText()).isIn("100","200");
    }

    @Test void exportsQuoteUnicodeMultilineAndNeutralizeFormulaCellsWithoutLeakingOwners() throws Exception {
        User user=new User();User other=new User();
        String walletName="  =SUM(1,2)",categoryName="+HYPERLINK(\"x\")",note="\t@SUM(1,1)\r\n\"Ghi chú\", tiếng Việt";
        jdbc.update("UPDATE wallets SET name=? WHERE id=?",walletName,user.a);jdbc.update("UPDATE categories SET name=? WHERE id=?",categoryName,user.expense);
        user.tx(user.expense,"EXPENSE","45",AT,note);
        UUID deleted=user.tx(user.expense,"EXPENSE","100",AT,"DELETED_SECRET");jdbc.update("UPDATE transactions SET deleted_at=now() WHERE id=?",deleted);
        user.tx(user.expense,"EXPENSE","7","2026-02-28T17:00:00Z","NEXT_MONTH_SECRET");
        other.tx(other.expense,"EXPENSE","99",AT,"FOREIGN_SECRET");
        var response=user.get("/api/v1/exports/transactions.csv?"+range());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/csv");
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).contains("attachment");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.body()).startsWith("\uFEFF").doesNotContain("DELETED_SECRET","FOREIGN_SECRET","NEXT_MONTH_SECRET");
        var rows=parseCsv(response.body().substring(1));assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).hasSize(8);assertThat(rows.get(1).get(3)).isEqualTo("45");
        assertThat(rows.get(1).get(5)).isEqualTo("'"+walletName);assertThat(rows.get(1).get(6)).isEqualTo("'"+categoryName);
        assertThat(rows.get(1).get(7)).isEqualTo("'"+note);
        var hidden=other.get("/api/v1/exports/transactions.csv?"+range()+"&walletId="+user.a);
        assertThat(parseCsv(hidden.body().substring(1))).hasSize(1);
    }

    @Test void exportLimitRejectsOversizedResultsAndAllowsExactlyTenThousand() throws Exception {
        User user=new User();
        jdbc.update("""
            INSERT INTO transactions(user_id,wallet_id,category_id,type,amount,occurred_at)
            SELECT ?,?,?,'EXPENSE',1,? FROM generate_series(1,10001)
            """,user.id,user.a,user.expense,OffsetDateTime.parse(AT));
        var tooLarge=user.get("/api/v1/exports/transactions.csv?"+range());assertThat(tooLarge.statusCode()).isEqualTo(413);
        assertThat(tooLarge.body()).doesNotStartWith("\uFEFF");
        jdbc.update("UPDATE transactions SET deleted_at=now() WHERE id=(SELECT id FROM transactions WHERE user_id=? LIMIT 1)",user.id);
        var accepted=user.get("/api/v1/exports/transactions.csv?"+range());assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(parseCsv(accepted.body().substring(1))).hasSize(10001);
    }

    @Test void monetaryAggregatesStayExactBeyondIndividualAmountLimits() throws Exception {
        User user=new User();
        user.tx(user.expense,"EXPENSE","999999999999999",AT,null);user.tx(user.expense,"EXPENSE","999999999999999",AT,null);
        assertThat(user.report("2026-02").get("totalExpense").asText()).isEqualTo("1999999999999998");
        assertThat(user.send("PUT","/api/v1/budgets/2026-02",replacement(user.expense,"999999999999999"),true).statusCode()).isEqualTo(200);
        assertThat(user.budget().get("budgetedSpent").asText()).isEqualTo("1999999999999998");
    }

    @Test void authenticationCsrfInvalidMonthsRangesAndProfilesAreHandled() throws Exception {
        User user=new User();
        for(String path:List.of("/api/v1/reports/summary?month=2026-02","/api/v1/budgets/2026-02","/api/v1/exports/transactions.csv?"+range())) {
            assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        assertThat(user.send("PUT","/api/v1/budgets/2026-02",replacement(user.expense,"100"),false).statusCode()).isEqualTo(403);
        assertThat(user.send("PATCH","/api/v1/me",Map.of("timezone","UTC"),false).statusCode()).isEqualTo(403);
        for(String month:List.of("2026-13","0000-01","2026-2","bad"))assertThat(user.get("/api/v1/reports/summary?month="+month).statusCode()).isEqualTo(400);
        assertThat(user.get("/api/v1/reports/summary").statusCode()).isEqualTo(400);
        assertThat(user.get("/api/v1/exports/transactions.csv").statusCode()).isEqualTo(400);
        assertThat(user.get("/api/v1/exports/transactions.csv?from=2026-03-01T00:00:00Z&to=2026-02-01T00:00:00Z").statusCode()).isEqualTo(400);
        assertThat(user.send("PATCH","/api/v1/me",Map.of(),true).statusCode()).isEqualTo(400);
        assertThat(user.send("PATCH","/api/v1/me",Map.of("email","forged@example.test"),true).statusCode()).isEqualTo(400);
    }

    // Independent RFC 4180-style reader: verifies exported cells, including embedded CR/LF and quotes.
    static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;
        for(int i=0;i<csv.length();i++) {
            char c=csv.charAt(i);
            if(c=='\"') {if(quoted && i+1<csv.length() && csv.charAt(i+1)=='\"'){cell.append('\"');i++;}else quoted=!quoted;}
            else if(c==',' && !quoted){row.add(cell.toString());cell.setLength(0);}
            else if(c=='\r' && !quoted && i+1<csv.length() && csv.charAt(i+1)=='\n'){row.add(cell.toString());cell.setLength(0);rows.add(row);row=new ArrayList<>();i++;}
            else cell.append(c);
        }
        assertThat(quoted).isFalse();assertThat(cell.length()).isZero();return rows;
    }
}
