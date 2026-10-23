package com.finstep.common.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean HttpSessionSecurityContextRepository contextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
    @Bean InMemoryUserDetailsManager userDetailsService() { return new InMemoryUserDetailsManager(); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthRateLimiter limits, JdbcTemplate jdbc,
                                           HttpSessionSecurityContextRepository contexts) throws Exception {
        return http
            .securityContext(context -> context.securityContextRepository(contexts))
            .authorizeHttpRequests(requests -> requests
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                    "/actuator/health/readiness", "/api/v1/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                    "/api/v1/auth/verify-email", "/api/v1/auth/resend-verification",
                    "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/me").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/wallets", "/api/v1/wallets/*",
                    "/api/v1/categories", "/api/v1/categories/*").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/wallets", "/api/v1/categories", "/api/v1/categories/defaults").authenticated()
                .requestMatchers(HttpMethod.PATCH, "/api/v1/wallets/*", "/api/v1/categories/*").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/transactions", "/api/v1/transactions/*", "/api/v1/transfers", "/api/v1/transfers/*").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/transactions", "/api/v1/transfers").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/transactions/*", "/api/v1/transfers/*").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/v1/transactions/*", "/api/v1/transfers/*").authenticated()
                .requestMatchers(HttpMethod.PATCH, "/api/v1/me").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/budgets/*", "/api/v1/reports/summary", "/api/v1/exports/transactions.csv").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/budgets/*").authenticated()
                .anyRequest().denyAll())
            .requestCache(AbstractHttpConfigurer::disable)
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request,response,error) -> response.setStatus(401))
                .accessDeniedHandler((request,response,error) -> response.setStatus(403)))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("SESSION")
                .logoutSuccessHandler((request,response,authentication) -> response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
            .addFilterBefore(new AuthRateLimitFilter(limits), CsrfFilter.class)
            .addFilterBefore(new AccountSessionFilter(jdbc), AuthorizationFilter.class)
            .build();
    }
}
