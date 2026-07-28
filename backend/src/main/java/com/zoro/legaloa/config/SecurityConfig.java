package com.zoro.legaloa.config;

import com.zoro.legaloa.identity.DevAuthenticationFilter;
import com.zoro.legaloa.identity.SessionAuthenticationFilter;
import com.zoro.legaloa.common.CorrelationIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            DevAuthenticationFilter devAuthenticationFilter,
            SessionAuthenticationFilter sessionAuthenticationFilter,
            CorrelationIdFilter correlationIdFilter,
            @Value("${app.auth.mode}") String authMode
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health/**",
                                "/api/public/**",
                                "/api/auth/config",
                                "/api/auth/dingtalk/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                );
        http.addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(sessionAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        if ("dev".equals(authMode)) {
            http.addFilterBefore(devAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }
}
