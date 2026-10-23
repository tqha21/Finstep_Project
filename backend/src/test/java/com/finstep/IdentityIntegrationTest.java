package com.finstep;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finstep.identity.MailGateway;
import com.finstep.identity.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.servlet.session.cookie.secure=false")
@Import(IdentityIntegrationTest.MailConfiguration.class)
class IdentityIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecordingMail mail;
    private static final String PASSWORD = "Correct-Horse-123!";

    static class RecordingMail implements MailGateway {
        final Map<String,String> tokens = new ConcurrentHashMap<>();
        boolean fail;
        public void sendToken(String recipient, String purpose, String token) {
            if (fail) throw new MailSendException("Simulated local delivery failure");
            tokens.put(recipient + ":" + purpose,token);
        }
        String token(String email,String purpose) { return tokens.get(email + ":" + purpose); }
    }
    @TestConfiguration
    static class MailConfiguration {
        @Bean @Primary RecordingMail testMail() { return new RecordingMail(); }
    }
    @BeforeEach void resetLimits() {
        jdbc.update("DELETE FROM auth_rate_limits");
        mail.fail = false;
    }

    class Browser {
        final CookieManager cookies = new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path,Object body) throws Exception { return post(path,body,true); }
        HttpResponse<String> post(String path,Object body,boolean csrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json");
            if (csrf) {
                JsonNode token = json.readTree(get("/api/v1/auth/csrf").body());
                request.header(token.get("headerName").asText(),token.get("token").asText());
            }
            return client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        String session() { return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("SESSION")).findFirst().orElseThrow().getValue(); }
    }
    String email() { return "user-" + UUID.randomUUID()+"@example.test"; }
    void register(Browser browser,String email) throws Exception {
        assertThat(browser.post("/api/v1/auth/register",Map.of("email",email,"password",PASSWORD,"fullName","Test User")).statusCode()).isEqualTo(202);
    }
    void verify(Browser browser,String email) throws Exception {
        assertThat(browser.post("/api/v1/auth/verify-email",Map.of("token",mail.token(email,"VERIFY"))).statusCode()).isEqualTo(204);
    }
    void login(Browser browser,String email,String password) throws Exception {
        assertThat(browser.post("/api/v1/auth/login",Map.of("email",email,"password",password)).statusCode()).isEqualTo(204);
    }

    @Test void registrationVerificationSessionRotationAndTenantIdentity() throws Exception {
        Browser first = new Browser(); String email = email();
        register(first,email);
        String token = mail.token(email,"VERIFY");
        assertThat(token).hasSize(43);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM identity_tokens WHERE token_hash=?",String.class,TokenHasher.hash(token)))
            .hasSize(64).isNotEqualTo(token);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE email=?",String.class,email)).startsWith("$2a$12$").isNotEqualTo(PASSWORD);
        assertThat(first.post("/api/v1/auth/login",Map.of("email",email,"password",PASSWORD)).statusCode()).isEqualTo(401);
        register(first,email); // duplicate still has the same generic response
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email=?",Integer.class,email)).isEqualTo(1);
        verify(first,email);
        assertThat(first.post("/api/v1/auth/verify-email",Map.of("token",token)).statusCode()).isEqualTo(400);
        String oldSession = first.session();
        login(first,email,PASSWORD);
        assertThat(first.session()).isNotEqualTo(oldSession);
        var profile = first.get("/api/v1/me");
        assertThat(profile.statusCode()).isEqualTo(200);
        assertThat(profile.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(json.readTree(profile.body()).get("email").asText()).isEqualTo(email);
        assertThat(profile.body()).doesNotContain("password","tokenHash");
        Browser second = new Browser(); String secondEmail = email();
        register(second,secondEmail); verify(second,secondEmail); login(second,secondEmail,PASSWORD);
        assertThat(json.readTree(second.get("/api/v1/me").body()).get("email").asText()).isEqualTo(secondEmail);
        assertThat(json.readTree(first.get("/api/v1/me").body()).get("email").asText()).isEqualTo(email);
        assertThat(first.cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("SESSION")).findFirst().orElseThrow().isHttpOnly()).isTrue();
    }

    @Test void resetRevokesAllSessionsAndSiblingTokens() throws Exception {
        Browser first = new Browser(); Browser second = new Browser(); Browser recovery = new Browser();
        String email = email(); register(first,email); verify(first,email);
        login(first,email,PASSWORD); login(second,email,PASSWORD);
        assertThat(recovery.post("/api/v1/auth/forgot-password",Map.of("email",email)).statusCode()).isEqualTo(202);
        String olderToken = mail.token(email,"RESET");
        assertThat(recovery.post("/api/v1/auth/forgot-password",Map.of("email",email)).statusCode()).isEqualTo(202);
        String token = mail.token(email,"RESET"); String replacement = "Another-Strong-Password-456!";
        assertThat(recovery.post("/api/v1/auth/reset-password",Map.of("token",token,"password",replacement)).statusCode()).isEqualTo(204);
        assertThat(first.get("/api/v1/me").statusCode()).isEqualTo(401);
        assertThat(second.get("/api/v1/me").statusCode()).isEqualTo(401);
        assertThat(recovery.post("/api/v1/auth/reset-password",Map.of("token",token,"password",PASSWORD)).statusCode()).isEqualTo(400);
        assertThat(recovery.post("/api/v1/auth/reset-password",Map.of("token",olderToken,"password",PASSWORD)).statusCode()).isEqualTo(400);
        assertThat(recovery.post("/api/v1/auth/login",Map.of("email",email,"password",PASSWORD)).statusCode()).isEqualTo(401);
        login(recovery,email,replacement);
    }

    @Test void csrfIsRequiredAndLogoutInvalidatesSession() throws Exception {
        Browser browser = new Browser(); String email=email();
        assertThat(browser.post("/api/v1/auth/register",Map.of("email",email,"password",PASSWORD,"fullName","Test"),false).statusCode()).isEqualTo(403);
        register(browser,email); verify(browser,email); login(browser,email,PASSWORD);
        assertThat(browser.post("/api/v1/auth/logout",Map.of(),false).statusCode()).isEqualTo(403);
        assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(200);
        String formerSession = browser.session();
        assertThat(browser.post("/api/v1/auth/logout",Map.of()).statusCode()).isEqualTo(204);
        var replay = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me"))
            .header("Cookie","SESSION="+formerSession).GET().build();
        assertThat(HttpClient.newHttpClient().send(replay,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(401);
    }

    @Test void expiredTokensDisabledAccountsAndInvalidPasswordsAreRejected() throws Exception {
        Browser browser = new Browser(); String email=email(); register(browser,email);
        String token=mail.token(email,"VERIFY");
        jdbc.update("UPDATE identity_tokens SET expires_at=now()-interval '1 minute' WHERE token_hash=?",TokenHasher.hash(token));
        assertThat(browser.post("/api/v1/auth/verify-email",Map.of("token",token)).statusCode()).isEqualTo(400);
        assertThat(browser.post("/api/v1/auth/resend-verification",Map.of("email",email)).statusCode()).isEqualTo(202);
        verify(browser,email); login(browser,email,PASSWORD);
        assertThat(browser.post("/api/v1/auth/login",Map.of("email",email,"password","đ".repeat(40))).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE users SET status='DISABLED' WHERE email=?",email);
        assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(401);
        assertThat(browser.post("/api/v1/auth/register",Map.of("email",email(),"password","short","fullName","Test")).statusCode()).isEqualTo(400);
        assertThat(browser.post("/api/v1/auth/register",Map.of("email",email(),"password","đ".repeat(40),"fullName","Test")).statusCode()).isEqualTo(400);
    }

    @Test void limitsLoginByAccountAndByNetworkAddress() throws Exception {
        Browser browser = new Browser(); String email=email();
        for (int i=0;i<10;i++) {
            assertThat(browser.post("/api/v1/auth/login",Map.of("email",email,"password",PASSWORD)).statusCode()).isEqualTo(401);
        }
        assertThat(browser.post("/api/v1/auth/login",Map.of("email",email,"password",PASSWORD)).statusCode()).isEqualTo(429);
        jdbc.update("DELETE FROM auth_rate_limits");
        for (int i=0;i<30;i++) {
            assertThat(browser.post("/api/v1/auth/login",Map.of(),false).statusCode()).isEqualTo(403);
        }
        assertThat(browser.post("/api/v1/auth/login",Map.of(),false).statusCode()).isEqualTo(429);
    }

    @Test void mailFailureRollsBackRegistrationAndUnknownEmailResponseIsGeneric() throws Exception {
        Browser browser = new Browser(); String email=email();
        mail.fail=true;
        assertThat(browser.post("/api/v1/auth/register",Map.of("email",email,"password",PASSWORD,"fullName","Test")).statusCode()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email=?",Integer.class,email)).isZero();
        assertThat(browser.post("/api/v1/auth/forgot-password",Map.of("email",email())).statusCode()).isEqualTo(202);
    }
}
