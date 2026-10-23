package com.finstep;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finstep.category.CategoryType;
import com.finstep.transaction.LedgerCommands;
import com.finstep.transaction.TransactionService;
import com.finstep.transaction.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties="server.servlet.session.cookie.secure=false")
class LedgerIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17");
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionService transactions;
    @Autowired TransferService transfers;
    static final String PASSWORD="Ledger-Test-Password-123!";
    static final String HASH=new BCryptPasswordEncoder(4).encode(PASSWORD);
    static final String AT="2026-02-01T10:00:00+07:00";
    class User {
        final UUID id=UUID.randomUUID();
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        final UUID a,b,income,expense;
        User() throws Exception {
            String email=id+"@example.test";
            jdbc.update("INSERT INTO users(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,?,'Ledger Test',now())",id,email,HASH);
            assertThat(send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),null,true).statusCode()).isEqualTo(204);
            a=createWallet("A");b=createWallet("B");
            JsonNode cats=json.readTree(get("/api/v1/categories").body());
            income=UUID.fromString(java.util.stream.StreamSupport.stream(cats.spliterator(),false)
                .filter(c->c.get("type").asText().equals("INCOME")).findFirst().orElseThrow().get("id").asText());
            expense=UUID.fromString(java.util.stream.StreamSupport.stream(cats.spliterator(),false)
                .filter(c->c.get("type").asText().equals("EXPENSE")).findFirst().orElseThrow().get("id").asText());
        }
        UUID createWallet(String name) throws Exception {
            var response=send("POST","/api/v1/wallets",Map.of("name",name,"type","CASH","openingBalance","2000000","effectiveAt","2026-01-01T00:00:00Z"),null,true);
            assertThat(response.statusCode()).isEqualTo(201);return UUID.fromString(json.readTree(response.body()).get("id").asText());
        }
        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> send(String method,String path,Object body,String key,boolean csrf) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
            if(key!=null)request.header("Idempotency-Key",key);
            if(csrf){var token=json.readTree(get("/api/v1/auth/csrf").body());request.header(token.get("headerName").asText(),token.get("token").asText());}
            return http.send(request.method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        Map<String,Object> tx(UUID wallet,UUID category,String type,Object amount) {
            return Map.of("walletId",wallet,"categoryId",category,"type",type,"amount",amount,"occurredAt",AT);
        }
        Map<String,Object> move(UUID from,UUID to,Object amount) {
            return Map.of("fromWalletId",from,"toWalletId",to,"amount",amount,"occurredAt",AT);
        }
        JsonNode create(String path,Object body,String key) throws Exception {
            var response=send("POST","/api/v1/"+path,body,key,true);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            var resource=json.readTree(response.body());
            assertThat(response.headers().firstValue("Location")).hasValue("/api/v1/"+path+"/"+resource.get("id").asText());
            return resource;
        }
        String balance(UUID wallet) throws Exception {return json.readTree(get("/api/v1/wallets/"+wallet).body()).get("balance").asText();}
    }

    @Test void incomeExpenseTransferEditingAndDeletionReconcileBalances() throws Exception {
        User user=new User();
        user.create("transactions",user.tx(user.a,user.income,"INCOME","5000000"),"salary");
        var expense=user.create("transactions",user.tx(user.a,user.expense,"EXPENSE","1500000"),"food");
        assertThat(user.balance(user.a)).isEqualTo("5500000");
        String path="/api/v1/transactions/"+expense.get("id").asText();
        assertThat(user.send("PUT",path,user.tx(user.a,user.expense,"EXPENSE","1000000"),null,true).statusCode()).isEqualTo(200);
        assertThat(user.balance(user.a)).isEqualTo("6000000");
        assertThat(user.send("DELETE",path,Map.of(),null,true).statusCode()).isEqualTo(204);
        assertThat(user.send("DELETE",path,Map.of(),null,true).statusCode()).isEqualTo(204);
        assertThat(user.get(path).statusCode()).isEqualTo(404);
        var transfer=user.create("transfers",user.move(user.a,user.b,"1000000"),"move");
        String movePath="/api/v1/transfers/"+transfer.get("id").asText();
        var transferReplay=user.send("POST","/api/v1/transfers",user.move(user.a,user.b,"1000000"),"move",true);
        assertThat(transferReplay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(transferReplay.body()).get("id").asText()).isEqualTo(transfer.get("id").asText());
        assertThat(user.balance(user.a)).isEqualTo("6000000");assertThat(user.balance(user.b)).isEqualTo("3000000");
        assertThat(user.send("PUT",movePath,user.move(user.b,user.a,"500000"),null,true).statusCode()).isEqualTo(200);
        assertThat(user.balance(user.a)).isEqualTo("7500000");assertThat(user.balance(user.b)).isEqualTo("1500000");
        assertThat(user.send("DELETE",movePath,Map.of(),null,true).statusCode()).isEqualTo(204);
        assertThat(user.balance(user.a)).isEqualTo("7000000");assertThat(user.balance(user.b)).isEqualTo("2000000");
        assertThat(json.readTree(user.send("POST","/api/v1/transfers",user.move(user.a,user.b,"1000000"),"move",true).body()).get("deleted").asBoolean()).isTrue();
    }

    @Test void idempotencyScopesKeysAndDoesNotResurrectEditedOrDeletedTransactions() throws Exception {
        User user=new User();var body=user.tx(user.a,user.expense,"EXPENSE","100");
        var first=user.create("transactions",body,"same");String id=first.get("id").asText();
        var retry=user.send("POST","/api/v1/transactions",body,"same",true);
        assertThat(retry.statusCode()).isEqualTo(200);assertThat(json.readTree(retry.body()).get("id").asText()).isEqualTo(id);
        var offset=new HashMap<>(body);offset.put("occurredAt","2026-02-01T03:00:00Z");
        assertThat(user.send("POST","/api/v1/transactions",offset,"same",true).statusCode()).isEqualTo(200);
        assertThat(user.send("POST","/api/v1/transactions",user.tx(user.a,user.expense,"EXPENSE","101"),"same",true).statusCode()).isEqualTo(409);
        assertThat(user.send("POST","/api/v1/transfers",user.move(user.a,user.b,"100"),"same",true).statusCode()).isEqualTo(409);
        assertThat(user.send("PUT","/api/v1/transactions/"+id,user.tx(user.a,user.expense,"EXPENSE","200"),null,true).statusCode()).isEqualTo(200);
        assertThat(json.readTree(user.send("POST","/api/v1/transactions",body,"same",true).body()).get("amount").asText()).isEqualTo("200");
        assertThat(user.send("DELETE","/api/v1/transactions/"+id,Map.of(),null,true).statusCode()).isEqualTo(204);
        assertThat(json.readTree(user.send("POST","/api/v1/transactions",body,"same",true).body()).get("deleted").asBoolean()).isTrue();
        assertThat(user.balance(user.a)).isEqualTo("2000000");
        User other=new User();other.create("transactions",other.tx(other.a,other.expense,"EXPENSE","100"),"same");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE user_id=?",Integer.class,user.id)).isEqualTo(1);
    }

    @Test void failedWritesRollBackTheIdempotencyClaimAndDatabaseFailureIsAtomic() throws Exception {
        User user=new User();
        assertThat(user.send("POST","/api/v1/transactions",user.tx(user.a,user.income,"EXPENSE","100"),"retry-after-error",true).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_requests WHERE user_id=?",Integer.class,user.id)).isZero();
        user.create("transactions",user.tx(user.a,user.expense,"EXPENSE","100"),"retry-after-error");
        // Direct service call deliberately hits the DB constraint, after a claim has been inserted.
        var invalid=new LedgerCommands.Transfer(user.a,user.b,"0",OffsetDateTime.parse(AT),null);
        assertThatThrownBy(()->transfers.create(user.id,"db-failure",invalid)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_requests WHERE user_id=? AND key='db-failure'",Integer.class,user.id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE user_id=?",Integer.class,user.id)).isZero();
        assertThat(user.balance(user.b)).isEqualTo("2000000");
    }

    @Test void crossOwnerResourcesAreNeverReadableWritableOrUsableAsReferences() throws Exception {
        User owner=new User();User stranger=new User();
        var tx=owner.create("transactions",owner.tx(owner.a,owner.expense,"EXPENSE","100"),"private");
        var move=owner.create("transfers",owner.move(owner.a,owner.b,"100"),"private-move");
        for(String path:List.of("/api/v1/transactions/"+tx.get("id").asText(),"/api/v1/transfers/"+move.get("id").asText())) {
            assertThat(stranger.get(path).statusCode()).isEqualTo(404);
            Object body=path.contains("/transactions/")?stranger.tx(stranger.a,stranger.expense,"EXPENSE","100"):stranger.move(stranger.a,stranger.b,"100");
            assertThat(stranger.send("PUT",path,body,null,true).statusCode()).isEqualTo(404);
            assertThat(stranger.send("DELETE",path,Map.of(),null,true).statusCode()).isEqualTo(404);
        }
        assertThat(stranger.send("POST","/api/v1/transactions",stranger.tx(owner.a,stranger.expense,"EXPENSE","100"),"foreign-wallet",true).statusCode()).isEqualTo(404);
        assertThat(stranger.send("POST","/api/v1/transactions",stranger.tx(stranger.a,owner.expense,"EXPENSE","100"),"foreign-category",true).statusCode()).isEqualTo(404);
        assertThat(stranger.send("POST","/api/v1/transfers",stranger.move(stranger.a,owner.b,"100"),"foreign-transfer",true).statusCode()).isEqualTo(404);
        assertThat(json.readTree(stranger.get("/api/v1/transactions?walletId="+owner.a).body()).get("items").isEmpty()).isTrue();
        assertThat(json.readTree(stranger.get("/api/v1/transfers").body()).get("items").isEmpty()).isTrue();
    }

    @Test void archivedResourcesDatesAmountsAndCsrfAreValidated() throws Exception {
        User user=new User();var body=user.tx(user.a,user.expense,"EXPENSE","100");
        assertThat(user.send("POST","/api/v1/transactions",body,null,true).statusCode()).isEqualTo(400);
        assertThat(user.send("POST","/api/v1/transactions",body,"bad key",true).statusCode()).isEqualTo(400);
        assertThat(user.send("POST","/api/v1/transactions",body,"no-csrf",false).statusCode()).isEqualTo(403);
        for(Object amount:List.of("0","-1","1.5","1000000000000000",100)) {
            assertThat(user.send("POST","/api/v1/transactions",user.tx(user.a,user.expense,"EXPENSE",amount),UUID.randomUUID().toString(),true).statusCode()).isEqualTo(400);
        }
        for(String date:List.of("2025-12-31T00:00:00Z",OffsetDateTime.now().plusDays(1).toString())) {
            var bad=new HashMap<>(body);bad.put("occurredAt",date);
            assertThat(user.send("POST","/api/v1/transactions",bad,UUID.randomUUID().toString(),true).statusCode()).isEqualTo(400);
        }
        assertThat(user.send("POST","/api/v1/transfers",user.move(user.a,user.a,"100"),"self",true).statusCode()).isEqualTo(400);
        var created=user.create("transactions",body,"valid");String path="/api/v1/transactions/"+created.get("id").asText();
        assertThat(user.send("PUT",path,body,null,false).statusCode()).isEqualTo(403);
        assertThat(user.send("DELETE",path,Map.of(),null,false).statusCode()).isEqualTo(403);
        assertThat(user.send("PATCH","/api/v1/categories/"+user.expense,Map.of("archived",true),null,true).statusCode()).isEqualTo(200);
        assertThat(user.send("POST","/api/v1/transactions",body,"archived-category",true).statusCode()).isEqualTo(409);
        assertThat(user.send("PATCH","/api/v1/wallets/"+user.a,Map.of("archived",true),null,true).statusCode()).isEqualTo(200);
        assertThat(user.send("POST","/api/v1/transfers",user.move(user.a,user.b,"100"),"archived-wallet",true).statusCode()).isEqualTo(409);
        assertThat(user.send("PUT",path,body,null,true).statusCode()).isEqualTo(409);
        assertThat(user.send("DELETE",path,Map.of(),null,true).statusCode()).isEqualTo(204);
    }

    @Test void cursorPaginationHandlesEqualTimestampsAndFiltersWithHalfOpenDates() throws Exception {
        User user=new User();Set<String> expected=new HashSet<>();
        for(int i=0;i<3;i++)expected.add(user.create("transactions",user.tx(user.a,user.expense,"EXPENSE",String.valueOf(i+1)),"tie-"+i).get("id").asText());
        String path="/api/v1/transactions?size=1";Set<String> found=new HashSet<>();
        for(int page=0;page<3;page++) {
            JsonNode response=json.readTree(user.get(path).body());assertThat(response.get("items").size()).isEqualTo(1);
            assertThat(found.add(response.get("items").get(0).get("id").asText())).isTrue();
            if(page<2)path="/api/v1/transactions?size=1&cursor="+response.get("nextCursor").asText();
            else assertThat(response.get("nextCursor").isNull()).isTrue();
        }
        assertThat(found).isEqualTo(expected);
        assertThat(json.readTree(user.get("/api/v1/transactions?type=INCOME").body()).get("items").isEmpty()).isTrue();
        assertThat(json.readTree(user.get("/api/v1/transactions?walletId="+user.b).body()).get("items").isEmpty()).isTrue();
        assertThat(json.readTree(user.get("/api/v1/transactions?categoryId="+user.expense).body()).get("items").size()).isEqualTo(3);
        String at=URLEncoder.encode(AT,StandardCharsets.UTF_8);
        assertThat(json.readTree(user.get("/api/v1/transactions?from="+at).body()).get("items").size()).isEqualTo(3);
        assertThat(json.readTree(user.get("/api/v1/transactions?to="+at).body()).get("items").isEmpty()).isTrue();
        user.create("transfers",user.move(user.a,user.b,"100"),"filter-move");
        user.create("transfers",user.move(user.b,user.a,"50"),"filter-move-2");
        for(UUID wallet:List.of(user.a,user.b))assertThat(json.readTree(user.get("/api/v1/transfers?walletId="+wallet).body()).get("items").size()).isEqualTo(2);
        JsonNode movePage=json.readTree(user.get("/api/v1/transfers?size=1").body());
        JsonNode lastMove=json.readTree(user.get("/api/v1/transfers?size=1&cursor="+movePage.get("nextCursor").asText()).body());
        assertThat(lastMove.get("nextCursor").isNull()).isTrue();
        assertThat(lastMove.get("items").get(0).get("id").asText()).isNotEqualTo(movePage.get("items").get(0).get("id").asText());
        for(String query:List.of("size=0","size=101","cursor=bad","from="+at+"&to="+at))
            assertThat(user.get("/api/v1/transactions?"+query).statusCode()).isEqualTo(400);
    }

    @Test void simultaneousRetriesCreateExactlyOneTransaction() throws Exception {
        User user=new User();
        var body=new LedgerCommands.Transaction(user.a,user.expense,CategoryType.EXPENSE,"100",OffsetDateTime.parse(AT),null);
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{start.await();return transactions.create(user.id,"concurrent",body);});
            var second=pool.submit(()->{start.await();return transactions.create(user.id,"concurrent",body);});
            start.countDown();var a=first.get(20,TimeUnit.SECONDS);var b=second.get(20,TimeUnit.SECONDS);
            assertThat(a.resource().id()).isEqualTo(b.resource().id());assertThat(a.replay()).isNotEqualTo(b.replay());
        }
        assertThat(user.balance(user.a)).isEqualTo("1999900");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE user_id=?",Integer.class,user.id)).isEqualTo(1);
    }

    @Test void concurrentOppositeTransfersConserveBothBalancesWithoutDeadlock() throws Exception {
        User user=new User();CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{start.await();return transfers.create(user.id,"forward",new LedgerCommands.Transfer(user.a,user.b,"100",OffsetDateTime.parse(AT),null));});
            var second=pool.submit(()->{start.await();return transfers.create(user.id,"back",new LedgerCommands.Transfer(user.b,user.a,"100",OffsetDateTime.parse(AT),null));});
            start.countDown();first.get(20,TimeUnit.SECONDS);second.get(20,TimeUnit.SECONDS);
        }
        assertThat(user.balance(user.a)).isEqualTo("2000000");assertThat(user.balance(user.b)).isEqualTo("2000000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE user_id=?",Integer.class,user.id)).isEqualTo(2);
    }
}
