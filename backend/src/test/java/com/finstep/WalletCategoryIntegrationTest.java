package com.finstep;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finstep.category.CategoryService;
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

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.servlet.session.cookie.secure=false")
class WalletCategoryIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17");
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CategoryService categories;
    private static final String PASSWORD="Wallet-Test-Password-123!";
    // Fixtures bypass email verification; identity flows have their own integration suite.
    private static final String HASH=new BCryptPasswordEncoder(4).encode(PASSWORD);
    class Browser {
        final UUID owner=UUID.randomUUID();
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        Browser() throws Exception {
            String email=owner+"@example.test";
            jdbc.update("INSERT INTO users(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,?,'Wallet Test',now())",owner,email,HASH);
            assertThat(send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),true).statusCode()).isEqualTo(204);
        }
        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> send(String method,String path,Object body,boolean withCsrf) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
            if(withCsrf) {
                var csrf=json.readTree(get("/api/v1/auth/csrf").body());
                request.header(csrf.get("headerName").asText(),csrf.get("token").asText());
            }
            return http.send(request.method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        JsonNode wallet(String name,String amount) throws Exception {
            var response=send("POST","/api/v1/wallets",walletBody(name,amount),true);
            assertThat(response.statusCode()).isEqualTo(201);
            var created=json.readTree(response.body());
            assertThat(response.headers().firstValue("Location")).hasValue("/api/v1/wallets/"+created.get("id").asText());
            return created;
        }
    }
    Map<String,Object> walletBody(String name,Object amount) {
        return Map.of("name",name,"type","CASH","openingBalance",amount,"effectiveAt","2026-01-01T00:00:00+07:00");
    }

    @Test void walletCreationSeedsDefaultsAndPreservesExactMoneyAndArchival() throws Exception {
        Browser user=new Browser();
        JsonNode wallet=user.wallet("  Tiền mặt  ","999999999999999");
        String id=wallet.get("id").asText();
        assertThat(wallet.get("name").asText()).isEqualTo("Tiền mặt");
        assertThat(wallet.get("balance").asText()).isEqualTo("999999999999999");
        assertThat(wallet.get("openingBalance").isTextual()).isTrue();
        assertThat(wallet.get("currency").asText()).isEqualTo("VND");
        assertThat(json.readTree(user.get("/api/v1/categories").body()).size()).isEqualTo(9);
        user.wallet("Ngân hàng","-250000");
        assertThat(json.readTree(user.get("/api/v1/categories").body()).size()).isEqualTo(9);
        assertThat(user.send("PATCH","/api/v1/wallets/"+id,Map.of("archived",true,"name","Đã lưu trữ"),true).statusCode()).isEqualTo(200);
        assertThat(json.readTree(user.get("/api/v1/wallets").body()).size()).isEqualTo(1);
        assertThat(json.readTree(user.get("/api/v1/wallets?includeArchived=true").body()).size()).isEqualTo(2);
        assertThat(json.readTree(user.get("/api/v1/wallets/"+id).body()).get("balance").asText()).isEqualTo("999999999999999");
        assertThat(user.send("PATCH","/api/v1/wallets/"+id,Map.of("archived",false),true).statusCode()).isEqualTo(200);
    }

    @Test void foreignOwnersCannotReadOrModifyWalletsAndCategories() throws Exception {
        Browser owner=new Browser(); Browser stranger=new Browser();
        String wallet=owner.wallet("Private","1000").get("id").asText();
        String category=json.readTree(owner.get("/api/v1/categories").body()).get(0).get("id").asText();
        for(String path: new String[]{"/api/v1/wallets/"+wallet,"/api/v1/categories/"+category}) {
            assertThat(stranger.get(path).statusCode()).isEqualTo(404);
            assertThat(stranger.send("PATCH",path,Map.of("name","Stolen","archived",true),true).statusCode()).isEqualTo(404);
        }
        assertThat(json.readTree(stranger.get("/api/v1/wallets").body()).isEmpty()).isTrue();
        assertThat(json.readTree(stranger.get("/api/v1/categories").body()).isEmpty()).isTrue();
        assertThat(json.readTree(owner.get("/api/v1/wallets/"+wallet).body()).get("name").asText()).isEqualTo("Private");
        var forged=new java.util.HashMap<>(walletBody("Forged","0"));forged.put("user_id",owner.owner.toString());
        assertThat(stranger.send("POST","/api/v1/wallets",forged,true).statusCode()).isEqualTo(400);
        assertThat(stranger.get("/api/v1/wallets/"+UUID.randomUUID()).statusCode()).isEqualTo(404);
    }

    @Test void validationRejectsInexactMoneyFutureDatesAndImmutableFields() throws Exception {
        Browser user=new Browser();
        for(Object amount:new Object[]{"1.5","1e3","1000000000000000",123,"", "--1"}) {
            assertThat(user.send("POST","/api/v1/wallets",walletBody("Invalid",amount),true).statusCode()).isEqualTo(400);
        }
        var future=new java.util.HashMap<>(walletBody("Future","0"));future.put("effectiveAt",OffsetDateTime.now().plusDays(1).toString());
        assertThat(user.send("POST","/api/v1/wallets",future,true).statusCode()).isEqualTo(400);
        assertThat(user.send("POST","/api/v1/wallets",walletBody("  ","0"),true).statusCode()).isEqualTo(400);
        assertThat(json.readTree(user.get("/api/v1/wallets").body()).isEmpty()).isTrue();
        String id=user.wallet("Cash","0").get("id").asText();
        assertThat(user.send("PATCH","/api/v1/wallets/"+id,Map.of("openingBalance","10"),true).statusCode()).isEqualTo(400);
        assertThat(user.send("PATCH","/api/v1/wallets/"+id,Map.of("name","  "),true).statusCode()).isEqualTo(400);
        assertThat(user.send("PATCH","/api/v1/wallets/"+id,Map.of(),true).statusCode()).isEqualTo(400);
        assertThat(user.get("/api/v1/wallets/not-a-uuid").statusCode()).isEqualTo(400);
    }

    @Test void categoriesAreCustomizableAndDefaultsNeverOverwriteUserChoices() throws Exception {
        Browser user=new Browser();
        JsonNode defaults=json.readTree(user.send("POST","/api/v1/categories/defaults",Map.of(),true).body());
        String id=defaults.get(0).get("id").asText();
        assertThat(user.send("PATCH","/api/v1/categories/"+id,Map.of("name","Tên riêng","archived",true),true).statusCode()).isEqualTo(200);
        user.wallet("Cash","0");
        assertThat(user.send("POST","/api/v1/categories/defaults",Map.of(),true).statusCode()).isEqualTo(200);
        JsonNode preserved=json.readTree(user.get("/api/v1/categories/"+id).body());
        assertThat(preserved.get("name").asText()).isEqualTo("Tên riêng");
        assertThat(preserved.get("archived").asBoolean()).isTrue();
        var custom=user.send("POST","/api/v1/categories",Map.of("name","Học phí","type","EXPENSE"),true);
        assertThat(custom.statusCode()).isEqualTo(201);
        String customId=json.readTree(custom.body()).get("id").asText();
        assertThat(user.send("PATCH","/api/v1/categories/"+customId,Map.of("type","INCOME"),true).statusCode()).isEqualTo(400);
        for(JsonNode row:json.readTree(user.get("/api/v1/categories?type=EXPENSE").body())) {
            assertThat(row.get("type").asText()).isEqualTo("EXPENSE");
            assertThat(row.get("archived").asBoolean()).isFalse();
        }
        assertThat(json.readTree(user.get("/api/v1/categories?includeArchived=true").body()).size()).isEqualTo(10);
    }

    @Test void csrfAndHistoryProtectionRemainInForce() throws Exception {
        Browser user=new Browser();
        assertThat(user.send("POST","/api/v1/wallets",walletBody("Invalid","0"),false).statusCode()).isEqualTo(403);
        assertThat(user.send("POST","/api/v1/categories",Map.of("name","Invalid","type","INCOME"),false).statusCode()).isEqualTo(403);
        String wallet=user.wallet("History","1000").get("id").asText();
        String category=json.readTree(user.get("/api/v1/categories?type=EXPENSE").body()).get(0).get("id").asText();
        jdbc.update("INSERT INTO transactions(user_id,wallet_id,category_id,type,amount,occurred_at) VALUES(?,?,?,'EXPENSE',200,now())",
            user.owner,UUID.fromString(wallet),UUID.fromString(category));
        assertThat(user.send("PATCH","/api/v1/wallets/"+wallet,Map.of("archived",true),true).statusCode()).isEqualTo(200);
        assertThat(user.send("DELETE","/api/v1/wallets/"+wallet,Map.of(),true).statusCode()).isEqualTo(403);
        assertThat(json.readTree(user.get("/api/v1/wallets/"+wallet).body()).get("balance").asText()).isEqualTo("800");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE wallet_id=?",Integer.class,UUID.fromString(wallet))).isEqualTo(1);
    }

    @Test void concurrentDefaultSeedingCreatesExactlyOneSet() throws Exception {
        Browser user=new Browser();
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> {start.await();return categories.defaults(user.owner);});
            var second=pool.submit(() -> {start.await();return categories.defaults(user.owner);});
            start.countDown();
            assertThat(first.get(20,TimeUnit.SECONDS)).hasSize(9);
            assertThat(second.get(20,TimeUnit.SECONDS)).hasSize(9);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE user_id=?",Integer.class,user.owner)).isEqualTo(9);
    }
}
