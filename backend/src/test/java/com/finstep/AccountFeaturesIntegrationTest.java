package com.finstep;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finstep.identity.IdentityService;
import com.finstep.identity.TokenHasher;
import com.finstep.identity.UserPrincipal;
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
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.session.cookie.secure=false")
class AccountFeaturesIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired IdentityService identity;
    static final String PASSWORD="Account-Fixture-Password-123!", NEW="Changed-Fixture-Password-456!";
    static final String HASH=new BCryptPasswordEncoder(4).encode(PASSWORD);
    @BeforeEach void resetLimits() { jdbc.update("DELETE FROM auth_rate_limits"); }
    class Browser {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
        HttpResponse<String> get(String path) throws Exception { return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
        HttpResponse<String> send(String method,String path,Object body,boolean csrf,String key) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
            if(csrf) {var token=json.readTree(get("/api/v1/auth/csrf").body()); request.header(token.get("headerName").asText(),token.get("token").asText());}
            if(key!=null) request.header("Idempotency-Key",key);
            return client.send(request.method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> password(String current,String next) throws Exception {return send("POST","/api/v1/me/password",Map.of("currentPassword",current,"newPassword",next),true,null);}
        HttpResponse<String> feedback(String key,String message) throws Exception {return send("POST","/api/v1/feedback",Map.of("type","BUG","message",message),true,key);}
        HttpResponse<String> login(User user,String password) throws Exception {return send("POST","/api/v1/auth/login",Map.of("email",user.email,"password",password),true,null);}
    }
    class User extends Browser {
        final UUID id=UUID.randomUUID(); final String email=id+"@example.test";
        User() throws Exception {
            jdbc.update("INSERT INTO users(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,?,'Account Fixture',now())",id,email,HASH);
            // Token is unique per user, rather than a shared fixed hash.
            jdbc.update("INSERT INTO identity_tokens(user_id,token_hash,purpose,expires_at) VALUES(?,?,'RESET',now()+interval '1 hour')",id,TokenHasher.hash(id.toString()));
            assertThat(login(this,PASSWORD).statusCode()).isEqualTo(204);
        }
    }
    int version(User user) { return jdbc.queryForObject("SELECT auth_version FROM users WHERE id=?",Integer.class,user.id); }
    @Test void passwordChangeRevokesAllSessionsAndResetTokensWithoutTouchingAnotherAccount() throws Exception {
        User user=new User(),stranger=new User(); Browser second=new Browser(); assertThat(second.login(user,PASSWORD).statusCode()).isEqualTo(204);
        var response=user.password(PASSWORD,NEW); assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.headers().allValues("Set-Cookie")).anyMatch(value->value.contains("SESSION=")&&value.contains("Max-Age=0"));
        assertThat(version(user)).isEqualTo(1); assertThat(version(stranger)).isZero();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,user.id)).startsWith("$2a$12$").isNotEqualTo(HASH);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_tokens WHERE user_id=? AND consumed_at IS NULL",Integer.class,user.id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name=?",Integer.class,user.id.toString())).isZero();
        assertThat(second.get("/api/v1/me").statusCode()).isEqualTo(401); assertThat(user.get("/api/v1/me").statusCode()).isEqualTo(401);
        assertThat(stranger.get("/api/v1/me").statusCode()).isEqualTo(200);
        assertThat(user.login(user,PASSWORD).statusCode()).isEqualTo(401); assertThat(user.login(user,NEW).statusCode()).isEqualTo(204);
    }
    @Test void passwordRequiresSessionCsrfCorrectCurrentPasswordAndValidDistinctNewPassword() throws Exception {
        User user=new User(); Browser anonymous=new Browser();
        var body=Map.of("currentPassword",PASSWORD,"newPassword",NEW);
        assertThat(anonymous.send("POST","/api/v1/me/password",body,true,null).statusCode()).isEqualTo(401);
        assertThat(user.send("POST","/api/v1/me/password",body,false,null).statusCode()).isEqualTo(403);
        assertThat(user.password("Wrong-Password-123!",NEW).statusCode()).isEqualTo(400);
        assertThat(user.password(PASSWORD,"short").statusCode()).isEqualTo(400);
        assertThat(user.password(PASSWORD,"đ".repeat(40)).statusCode()).isEqualTo(400);
        assertThat(user.password(PASSWORD,PASSWORD).statusCode()).isEqualTo(400);
        assertThat(user.send("POST","/api/v1/me/password",Map.of("currentPassword",PASSWORD,"newPassword",NEW,"userId",UUID.randomUUID()),true,null).statusCode()).isEqualTo(400);
        assertThat(version(user)).isZero(); assertThat(user.get("/api/v1/me").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_tokens WHERE user_id=? AND consumed_at IS NULL",Integer.class,user.id)).isEqualTo(1);
    }
    @Test void failedPasswordAttemptsConsumeRateLimitEvenWhenTheBusinessTransactionRollsBack() throws Exception {
        User user=new User(); for(int i=0;i<5;i++) assertThat(user.password("Wrong-Password-123!",NEW).statusCode()).isEqualTo(400);
        assertThat(user.password(PASSWORD,NEW).statusCode()).isEqualTo(429); assertThat(version(user)).isZero();
    }
    @Test void concurrentPasswordChangesOnlyAllowOneWinner() throws Exception {
        User user=new User(); Browser second=new Browser(); second.login(user,PASSWORD);
        var start=new CountDownLatch(1); String alternative="Alternative-New-Password-789!";
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->{start.await();return user.password(PASSWORD,NEW);});
            var other=executor.submit(()->{start.await();return second.password(PASSWORD,alternative);});
            start.countDown(); int a=first.get(15,TimeUnit.SECONDS).statusCode(),b=other.get(15,TimeUnit.SECONDS).statusCode();
            assertThat(List.of(a,b).stream().filter(status->status==204).count()).isEqualTo(1);
            assertThat(a==204?b:a).isIn(401,403);
            assertThat(new Browser().login(user,a==204?NEW:alternative).statusCode()).isEqualTo(204);
        }
        assertThat(version(user)).isEqualTo(1);
    }
    @Test void passwordSessionDeletionFailureRollsBackHashVersionAndTokenConsumption() throws Exception {
        User user=new User();
        jdbc.execute("CREATE FUNCTION fail_account_session_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture rollback'; END $$");
        jdbc.execute("CREATE TRIGGER fail_account_session_delete BEFORE DELETE ON spring_session FOR EACH ROW WHEN (OLD.principal_name='"+user.id+"') EXECUTE FUNCTION fail_account_session_delete()");
        try {
            assertThatThrownBy(()->identity.changePassword(new UserPrincipal(user.id,0),PASSWORD,NEW)).isInstanceOf(DataAccessException.class);
            assertThat(version(user)).isZero(); assertThat(user.get("/api/v1/me").statusCode()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,user.id)).isEqualTo(HASH);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_tokens WHERE user_id=? AND consumed_at IS NULL",Integer.class,user.id)).isEqualTo(1);
        } finally {jdbc.execute("DROP TRIGGER fail_account_session_delete ON spring_session");jdbc.execute("DROP FUNCTION fail_account_session_delete()");}
    }
    @Test void feedbackIsOwnedReplayableAndDoesNotExposeInternalKeysOrHashes() throws Exception {
        User user=new User(),stranger=new User(); String key=UUID.randomUUID().toString(),message="Feedback Unicode: lỗi nhập liệu <script>\nTest";
        var created=user.feedback(key,message); assertThat(created.statusCode()).isEqualTo(201);
        JsonNode data=json.readTree(created.body()); var replay=user.feedback(key,"  "+message+"  ");
        assertThat(replay.statusCode()).isEqualTo(200); assertThat(json.readTree(replay.body()).get("id")).isEqualTo(data.get("id"));
        assertThat(user.feedback(key,"A different message").statusCode()).isEqualTo(409);
        assertThat(stranger.feedback(key,"Foreign secret feedback").statusCode()).isEqualTo(201);
        var own=user.get("/api/v1/feedback?userId="+stranger.id); assertThat(json.readTree(own.body()).size()).isEqualTo(1);
        assertThat(own.body()).contains("Feedback Unicode").doesNotContain("Foreign secret",stranger.id.toString(),"requestKey","requestHash",key,HASH,"email");
        assertThat(user.get("/api/v1/feedback/"+data.get("id").asText()).statusCode()).isEqualTo(403);
    }
    @Test void feedbackValidationAndAuthorizationRejectInvalidBodiesBeforeSaving() throws Exception {
        User user=new User(); var body=Map.of("type","BUG","message","Valid feedback message");
        assertThat(new Browser().send("POST","/api/v1/feedback",body,true,"anon").statusCode()).isEqualTo(401);
        assertThat(user.send("POST","/api/v1/feedback",body,false,"no-csrf").statusCode()).isEqualTo(403);
        assertThat(user.send("POST","/api/v1/feedback",body,true,null).statusCode()).isEqualTo(400);
        assertThat(user.feedback("bad key","Valid feedback message").statusCode()).isEqualTo(400);
        assertThat(user.feedback("blank"," ".repeat(30)).statusCode()).isEqualTo(400);
        assertThat(user.feedback("short","short").statusCode()).isEqualTo(400);
        assertThat(user.feedback("long","x".repeat(2001)).statusCode()).isEqualTo(400);
        assertThat(user.send("POST","/api/v1/feedback",Map.of("type","INVALID","message","Valid feedback message"),true,"type").statusCode()).isEqualTo(400);
        assertThat(user.send("POST","/api/v1/feedback",Map.of("type","BUG","message","Valid feedback message","userId",UUID.randomUUID()),true,"owner").statusCode()).isEqualTo(400);
        assertThat(json.readTree(user.get("/api/v1/feedback").body()).size()).isZero();
    }
    @Test void concurrentFeedbackRetriesCreateExactlyOneRecord() throws Exception {
        User user=new User(); Browser second=new Browser(); second.login(user,PASSWORD); String key=UUID.randomUUID().toString();
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->{start.await();return user.feedback(key,"Concurrent feedback message");});
            var b=executor.submit(()->{start.await();return second.feedback(key,"Concurrent feedback message");});
            start.countDown(); var first=a.get(15,TimeUnit.SECONDS); var last=b.get(15,TimeUnit.SECONDS);
            assertThat(List.of(first.statusCode(),last.statusCode())).containsExactlyInAnyOrder(201,200);
            assertThat(json.readTree(first.body()).get("id")).isEqualTo(json.readTree(last.body()).get("id"));
        }
        assertThat(json.readTree(user.get("/api/v1/feedback").body()).size()).isEqualTo(1);
    }
    @Test void feedbackRateLimitPersistsAndLatestListIsBoundedWhileExportKeepsAllRows() throws Exception {
        User user=new User(); for(int i=0;i<5;i++) assertThat(user.feedback("rate-"+i,"Feedback message "+i).statusCode()).isEqualTo(201);
        assertThat(user.feedback("sixth","Feedback message six").statusCode()).isEqualTo(429);
        for(int i=0;i<20;i++) jdbc.update("INSERT INTO feedback(user_id,request_key,request_hash,type,message) VALUES(?,?,?,'IDEA','Earlier fixture feedback')",user.id,"seed-"+i,TokenHasher.hash("seed"));
        assertThat(json.readTree(user.get("/api/v1/feedback").body()).size()).isEqualTo(20);
        var export=user.get("/api/v1/me/export"); assertThat(export.statusCode()).isEqualTo(200);
        assertThat(json.readTree(export.body()).get("feedback").size()).isEqualTo(25);
    }
}
