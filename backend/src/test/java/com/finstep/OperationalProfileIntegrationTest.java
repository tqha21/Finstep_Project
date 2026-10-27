package com.finstep;

import com.finstep.identity.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@AutoConfigureObservability
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.profiles.active=staging","management.server.port=0","management.server.address=127.0.0.1",
    "server.address=127.0.0.1","server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1"})
class OperationalProfileIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17");
    @LocalServerPort int port;
    @LocalManagementPort int management;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void reset() { jdbc.update("DELETE FROM auth_rate_limits"); }
    @Test void prometheusIsOnTheSeparateManagementPortAndContainsNoCredentials() throws Exception {
        var client=HttpClient.newHttpClient();
        var metrics=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+management+"/actuator/prometheus")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("jvm_memory_used_bytes").doesNotContain("password_hash","DB_PASSWORD","token_hash");
        var publicRequest=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/prometheus")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(publicRequest.statusCode()).isNotEqualTo(200);
    }
    @Test void forwardedClientAddressIsUsedOnlyWhenTheImmediateProxyIsTrusted() throws Exception {
        for(String address:new String[]{"203.0.113.10","203.0.113.11"}) {
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/login"))
                .header("X-Forwarded-For",address).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(attempts(address)).isEqualTo(1);
        }
        try(var socket=new Socket()) {
            socket.bind(new InetSocketAddress("127.0.0.2",0)); socket.connect(new InetSocketAddress("127.0.0.1",port));socket.setSoTimeout(5000);
            socket.getOutputStream().write(("POST /api/v1/auth/login HTTP/1.1\r\nHost: localhost\r\nX-Forwarded-For: 198.51.100.1\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}").getBytes(StandardCharsets.US_ASCII));
            assertThat(new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8)).contains("403");
        }
        assertThat(attempts("127.0.0.2")).isEqualTo(1);assertThat(attempts("198.51.100.1")).isZero();
    }
    int attempts(String address) {
        return jdbc.queryForObject("SELECT coalesce(sum(attempts),0) FROM auth_rate_limits WHERE bucket_hash=?",Integer.class,TokenHasher.hash("ip:"+address));
    }
}
