package com.finstep;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finstep.identity.TokenHasher;
import com.finstep.identity.UserPrincipal;
import com.finstep.privacy.PrivacyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="server.servlet.session.cookie.secure=false")
class PrivacyIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PrivacyService privacy;
    static final String PASSWORD = "Privacy-Test-Password-123!";
    static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    static final String[] OWNED = {"wallets", "categories", "opening_balances", "transactions", "transfers", "budgets", "budget_items", "identity_tokens", "idempotency_requests"};
    @BeforeEach void limits() { jdbc.update("DELETE FROM auth_rate_limits"); }
    class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        HttpResponse<String> send(String method, String path, Object body, boolean csrf) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type", "application/json");
            if (csrf) { var token = json.readTree(get("/api/v1/auth/csrf").body()); builder.header(token.get("headerName").asText(), token.get("token").asText()); }
            return client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        void login(User user) throws Exception { assertThat(send("POST", "/api/v1/auth/login", Map.of("email",user.email,"password",PASSWORD), true).statusCode()).isEqualTo(204); }
    }
    class User extends Browser {
        final UUID id=UUID.randomUUID(), a=UUID.randomUUID(), b=UUID.randomUUID(), category=UUID.randomUUID(), budget=UUID.randomUUID();
        final String email=id+"@example.test", token=UUID.randomUUID().toString();
        User() throws Exception {
            jdbc.update("INSERT INTO users(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,?,'Privacy Fixture',now())",id,email,HASH);
            for (UUID wallet:List.of(a,b)) {
                jdbc.update("INSERT INTO wallets(id,user_id,name,type,is_archived) VALUES(?,?,'Archived wallet','CASH',true)",wallet,id);
                jdbc.update("INSERT INTO opening_balances(wallet_id,user_id,amount,effective_at) VALUES(?,?,1000,'2025-01-01T00:00:00Z')",wallet,id);
            }
            jdbc.update("INSERT INTO categories(id,user_id,name,type,is_archived) VALUES(?,?,'Archived category','EXPENSE',true)",category,id);
            tx("999999999999999","Formula =1+1, \"ghi chú\"\nTiếng Việt",false);
            tx("500","Deleted note",true);
            jdbc.update("INSERT INTO transfers(user_id,from_wallet_id,to_wallet_id,amount,occurred_at) VALUES(?,?,?,20,'2026-01-02T00:00:00Z')",id,a,b);
            jdbc.update("INSERT INTO transfers(user_id,from_wallet_id,to_wallet_id,amount,occurred_at,deleted_at) VALUES(?,?,?,30,'2026-01-02T00:00:00Z',now())",id,b,a);
            jdbc.update("INSERT INTO budgets(id,user_id,month) VALUES(?,?,'2026-01-01')",budget,id);
            jdbc.update("INSERT INTO budget_items(user_id,budget_id,category_id,limit_amount) VALUES(?,?,?,100)",id,budget,category);
            jdbc.update("INSERT INTO identity_tokens(user_id,token_hash,purpose,expires_at) VALUES(?,?,'RESET',now()+interval '1 hour')",id,TokenHasher.hash(token));
            jdbc.update("INSERT INTO idempotency_requests(user_id,key,request_hash,resource_id) VALUES(?,'private-retry-key',?,?)",id,TokenHasher.hash("request"),UUID.randomUUID());
            login(this);
        }
        void tx(String amount, String note, boolean deleted) {
            jdbc.update("INSERT INTO transactions(user_id,wallet_id,category_id,type,amount,note,occurred_at,deleted_at) VALUES(?,?,?,'EXPENSE',?,?, '2026-01-01T00:00:00Z',CASE WHEN ? THEN now() END)",id,a,category,Long.parseLong(amount),note,deleted);
        }
        HttpResponse<String> delete(Object body,boolean csrf) throws Exception { return send("DELETE", "/api/v1/me",body,csrf); }
    }
    Map<String,String> confirmation(String password) { return Map.of("password",password,"confirmation","XÓA TÀI KHOẢN"); }
    List<Integer> counts(User user) {
        return Arrays.stream(OWNED).map(table -> jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE user_id=?",Integer.class,user.id)).toList();
    }
    @Test void fullExportIncludesArchivedAndDeletedRowsButNoForeignDataOrCredentials() throws Exception {
        User user=new User(), stranger=new User(); stranger.tx("12","FOREIGN_PRIVATE_NOTE",false);
        var response=user.get("/api/v1/me/export?userId="+stranger.id);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition")).hasValue("attachment; filename=\"finstep-personal-data.json\"");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        var data=json.readTree(response.body());
        assertThat(data.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(data.get("account").get("id").asText()).isEqualTo(user.id.toString());
        assertThat(data.get("wallets").size()).isEqualTo(2);
        assertThat(data.get("wallets").get(0).get("archived").asBoolean()).isTrue();
        assertThat(data.get("wallets").get(0).get("openingBalance").asText()).isEqualTo("1000");
        assertThat(data.get("categories").get(0).get("archived").asBoolean()).isTrue();
        assertThat(data.get("transactions").size()).isEqualTo(2);
        assertThat(data.get("transactions").findValues("amount")).anyMatch(value -> value.isTextual() && value.asText().equals("999999999999999"));
        assertThat(data.get("transactions").findValues("deletedAt")).anyMatch(value -> !value.isNull());
        assertThat(data.get("transactions").findValuesAsText("note")).contains("Formula =1+1, \"ghi chú\"\nTiếng Việt");
        assertThat(data.get("transfers").size()).isEqualTo(2);
        assertThat(data.get("budgetItems").get(0).get("limitAmount").asText()).isEqualTo("100");
        assertThat(data.get("budgets").get(0).get("month").asText()).isEqualTo("2026-01-01");
        assertThat(response.body()).doesNotContain(stranger.email,stranger.id.toString(),"FOREIGN_PRIVATE_NOTE",HASH,PASSWORD,"password", "token", "authVersion", "spring_session", "private-retry-key");
        for(int i=0;i<2;i++) assertThat(user.get("/api/v1/me/export").statusCode()).isEqualTo(200);
        assertThat(user.get("/api/v1/me/export").statusCode()).isEqualTo(429);
    }
    @Test void deletionRequiresSessionCsrfCurrentPasswordExactConfirmationAndNoOwnerOverride() throws Exception {
        User user=new User(); var before=counts(user); Browser anonymous=new Browser();
        assertThat(anonymous.get("/api/v1/me/export").statusCode()).isEqualTo(401);
        assertThat(anonymous.send("DELETE","/api/v1/me",confirmation(PASSWORD),true).statusCode()).isEqualTo(401);
        assertThat(user.delete(confirmation(PASSWORD),false).statusCode()).isEqualTo(403);
        assertThat(user.delete(confirmation("Wrong-Password-123!"),true).statusCode()).isEqualTo(400);
        assertThat(user.delete(Map.of("password",PASSWORD,"confirmation","delete"),true).statusCode()).isEqualTo(400);
        assertThat(user.delete(Map.of("password",PASSWORD,"confirmation","XÓA TÀI KHOẢN","userId",UUID.randomUUID()),true).statusCode()).isEqualTo(400);
        assertThat(counts(user)).isEqualTo(before);
        assertThat(user.get("/api/v1/me").statusCode()).isEqualTo(200);
    }
    @Test void deletionRemovesAllOwnedDataAndEverySessionWithoutTouchingAnotherUser() throws Exception {
        User user=new User(), stranger=new User(); Browser second=new Browser(); second.login(user);
        var strangerBefore=counts(stranger);
        String cookie=user.cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("SESSION")).findFirst().orElseThrow().getValue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name=?",Integer.class,user.id.toString())).isEqualTo(2);
        var response=user.delete(confirmation(PASSWORD),true); assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.headers().allValues("Set-Cookie")).anyMatch(value -> value.contains("SESSION=") && value.contains("Max-Age=0"));
        assertThat(counts(user)).allMatch(value -> value==0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?",Integer.class,user.id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name=?",Integer.class,user.id.toString())).isZero();
        assertThat(counts(stranger)).isEqualTo(strangerBefore);
        assertThat(stranger.get("/api/v1/me/export").statusCode()).isEqualTo(200);
        assertThat(second.get("/api/v1/me").statusCode()).isEqualTo(401);
        assertThat(user.get("/api/v1/me/export").statusCode()).isEqualTo(401);
        assertThat(second.send("POST","/api/v1/auth/login",Map.of("email",user.email,"password",PASSWORD),true).statusCode()).isEqualTo(401);
        var replay=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me")).header("Cookie","SESSION="+cookie).GET().build();
        assertThat(HttpClient.newHttpClient().send(replay,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }
    @Test void incorrectPasswordAttemptsRemainRateLimitedAfterRollback() throws Exception {
        User user=new User();
        for(int i=0;i<5;i++) assertThat(user.delete(confirmation("Wrong-Password-123!"),true).statusCode()).isEqualTo(400);
        assertThat(user.delete(confirmation(PASSWORD),true).statusCode()).isEqualTo(429);
        assertThat(counts(user)).allMatch(value -> value>0);
    }
    @Test void databaseFailureRollsBackFinancialDataTokensAndSessionsTogether() throws Exception {
        User user=new User(); var before=counts(user);
        jdbc.execute("CREATE FUNCTION reject_fixture_deletion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test deletion rollback'; END $$");
        jdbc.execute("CREATE TRIGGER reject_fixture_deletion BEFORE DELETE ON users FOR EACH ROW WHEN (OLD.id='"+user.id+"'::uuid) EXECUTE FUNCTION reject_fixture_deletion()");
        try {
            assertThatThrownBy(() -> privacy.delete(new UserPrincipal(user.id,0),PASSWORD)).isInstanceOf(DataAccessException.class);
            assertThat(counts(user)).isEqualTo(before);
            assertThat(user.get("/api/v1/me").statusCode()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name=?",Integer.class,user.id.toString())).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER reject_fixture_deletion ON users"); jdbc.execute("DROP FUNCTION reject_fixture_deletion()");
        }
    }
    @Test void streamingExportKeepsOneSnapshotWhileConcurrentDataChanges() throws Exception {
        User user=new User();
        for(int i=0;i<300;i++) user.tx("1","snapshot-"+"x".repeat(900),false);
        var reached=new CountDownLatch(1); var proceed=new CountDownLatch(1); var bytes=new ByteArrayOutputStream();
        OutputStream slow=new OutputStream() {
            boolean paused;
            @Override public void write(int value) throws IOException { write(new byte[]{(byte)value},0,1); }
            @Override public void write(byte[] buffer,int offset,int length) throws IOException {
                if(!paused) { paused=true; reached.countDown(); try { if(!proceed.await(10,TimeUnit.SECONDS)) throw new IOException("Snapshot test timeout"); } catch(InterruptedException interrupted) {Thread.currentThread().interrupt(); throw new IOException(interrupted);} }
                bytes.write(buffer,offset,length);
            }
        };
        try(var executor=Executors.newSingleThreadExecutor()) {
            var export=executor.submit(() -> {privacy.export(new UserPrincipal(user.id,0),slow); return true;});
            try {
                assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();
                user.tx("999","AFTER_SNAPSHOT",false);
                jdbc.update("UPDATE budget_items SET limit_amount=999 WHERE user_id=?",user.id);
            } finally { proceed.countDown(); }
            assertThat(export.get(15,TimeUnit.SECONDS)).isTrue();
        }
        JsonNode data=json.readTree(bytes.toString(StandardCharsets.UTF_8));
        assertThat(data.get("transactions").size()).isEqualTo(302);
        assertThat(data.get("budgetItems").get(0).get("limitAmount").asText()).isEqualTo("100");
        assertThat(data.toString()).doesNotContain("AFTER_SNAPSHOT");
    }
    @Test void ledgerWriteRacingDeletionCannotResurrectTheAccountOrLeaveFinancialRows() throws Exception {
        User user=new User(); Browser writer=new Browser(); writer.login(user);
        jdbc.update("UPDATE wallets SET is_archived=false WHERE user_id=?",user.id);
        jdbc.update("UPDATE categories SET is_archived=false WHERE user_id=?",user.id);
        var csrf=json.readTree(writer.get("/api/v1/auth/csrf").body());
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/transactions"))
            .header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString())
            .header(csrf.get("headerName").asText(),csrf.get("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("walletId",user.a,"categoryId",user.category,
                "type","EXPENSE","amount","10","occurredAt","2026-01-02T00:00:00Z")))).build();
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var deletion=executor.submit(() -> {start.await(); return user.delete(confirmation(PASSWORD),true);});
            var write=executor.submit(() -> {start.await(); return writer.client.send(request,HttpResponse.BodyHandlers.ofString());});
            start.countDown();
            assertThat(deletion.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            assertThat(write.get(15,TimeUnit.SECONDS).statusCode()).isIn(201,401,403);
        }
        assertThat(counts(user)).allMatch(value -> value==0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?",Integer.class,user.id)).isZero();
        assertThat(writer.get("/api/v1/me").statusCode()).isEqualTo(401);
    }

}
