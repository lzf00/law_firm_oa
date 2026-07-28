package com.zoro.legaloa.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class DevAuthenticationFilterTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doesNotImplicitlyAuthenticateMissingDevelopmentHeader() throws Exception {
        DevAuthenticationFilter filter = new DevAuthenticationFilter("dev");
        AtomicReference<Authentication> observed = new AtomicReference<>();

        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (request, response) -> observed.set(
                        SecurityContextHolder.getContext().getAuthentication()
                )
        );

        assertThat(observed.get()).isNull();
    }

    @Test
    void authenticatesExplicitDevelopmentUserHeader() throws Exception {
        DevAuthenticationFilter filter = new DevAuthenticationFilter("dev");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Dev-User", " zhanglawyer ");
        AtomicReference<Authentication> observed = new AtomicReference<>();

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> observed.set(SecurityContextHolder.getContext().getAuthentication())
        );

        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().getName()).isEqualTo("zhanglawyer");
        assertThat(observed.get().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
    }

    @Test
    void ignoresDevelopmentHeaderOutsideDevelopmentMode() throws Exception {
        DevAuthenticationFilter filter = new DevAuthenticationFilter("dingtalk");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Dev-User", "admin");
        AtomicReference<Authentication> observed = new AtomicReference<>();

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> observed.set(SecurityContextHolder.getContext().getAuthentication())
        );

        assertThat(observed.get()).isNull();
    }
}
