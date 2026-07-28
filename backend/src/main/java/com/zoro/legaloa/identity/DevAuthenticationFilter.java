package com.zoro.legaloa.identity;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class DevAuthenticationFilter extends OncePerRequestFilter {
    private final String authMode;

    public DevAuthenticationFilter(@Value("${app.auth.mode}") String authMode) {
        this.authMode = authMode;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if ("dev".equals(authMode) && SecurityContextHolder.getContext().getAuthentication() == null) {
            String username = request.getHeader("X-Dev-User");
            if (username != null && !username.isBlank()) {
                var authentication = new UsernamePasswordAuthenticationToken(
                        username.trim(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }
}
