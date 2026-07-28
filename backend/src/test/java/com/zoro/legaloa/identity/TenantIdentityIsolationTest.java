package com.zoro.legaloa.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TenantIdentityIsolationTest {
    private static final UUID ORGANIZATION_A =
            UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID ORGANIZATION_B =
            UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID USER_A =
            UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID USER_B =
            UUID.fromString("00000000-0000-0000-0000-000000000402");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesDuplicateUsernamesByImmutableUserAndOrganizationIds() {
        IdentityDirectory directory = mock(IdentityDirectory.class);
        RequestActor actorA = new RequestActor(USER_A, ORGANIZATION_A, "alex", "Alex A");
        RequestActor actorB = new RequestActor(USER_B, ORGANIZATION_B, "alex", "Alex B");
        when(directory.findActiveById(USER_A, ORGANIZATION_A)).thenReturn(Optional.of(actorA));
        when(directory.findActiveById(USER_B, ORGANIZATION_B)).thenReturn(Optional.of(actorB));
        RequestActorProvider provider = new RequestActorProvider(mock(JdbcClient.class), directory, "dingtalk");

        authenticate(new SessionSubject(SessionSubject.CURRENT_VERSION, USER_A, ORGANIZATION_A, "alex"));
        assertThat(provider.current()).isEqualTo(actorA);

        authenticate(new SessionSubject(SessionSubject.CURRENT_VERSION, USER_B, ORGANIZATION_B, "alex"));
        assertThat(provider.current()).isEqualTo(actorB);
    }

    @Test
    void rejectsCrossTenantSessionEvenWhenUsernameMatches() {
        IdentityDirectory directory = mock(IdentityDirectory.class);
        when(directory.findActiveById(USER_A, ORGANIZATION_B)).thenReturn(Optional.empty());
        RequestActorProvider provider = new RequestActorProvider(mock(JdbcClient.class), directory, "dingtalk");
        authenticate(new SessionSubject(SessionSubject.CURRENT_VERSION, USER_A, ORGANIZATION_B, "alex"));

        assertThatThrownBy(provider::current)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("USER_NOT_FOUND");
                    assertThat(exception.status().value()).isEqualTo(401);
                });
    }

    @Test
    void scopesDuplicateExternalIdsToTheStateOrganization() {
        IdentityDirectory directory = mock(IdentityDirectory.class);
        DingTalkLoginService loginService = mock(DingTalkLoginService.class);
        DingTalkAuthClient authClient = mock(DingTalkAuthClient.class);
        SessionTokenService sessions = mock(SessionTokenService.class);
        BrowserSessionCookieService cookies = mock(BrowserSessionCookieService.class);
        AuditService audit = mock(AuditService.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        RequestActor actorA = new RequestActor(USER_A, ORGANIZATION_A, "alex", "Alex A");
        RequestActor actorB = new RequestActor(USER_B, ORGANIZATION_B, "alex", "Alex B");
        when(authClient.exchange("code")).thenReturn(
                new DingTalkAuthClient.DingTalkIdentity("shared-external-id", "Alex", null)
        );
        when(loginService.consumeState("state-a")).thenReturn(ORGANIZATION_A);
        when(loginService.consumeState("state-b")).thenReturn(ORGANIZATION_B);
        when(directory.findActiveByDingTalkId("shared-external-id", ORGANIZATION_A))
                .thenReturn(Optional.of(actorA));
        when(directory.findActiveByDingTalkId("shared-external-id", ORGANIZATION_B))
                .thenReturn(Optional.of(actorB));
        when(sessions.issue(actorA)).thenReturn("token-a");
        when(sessions.issue(actorB)).thenReturn("token-b");
        when(cookies.create("token-a", SessionTokenService.SESSION_TTL)).thenReturn("cookie-a");
        when(cookies.create("token-b", SessionTokenService.SESSION_TTL)).thenReturn("cookie-b");
        DingTalkAuthController controller = new DingTalkAuthController(
                authClient, sessions, audit, loginService, cookies, directory
        );

        assertThat(controller.exchange(
                new DingTalkAuthController.ExchangeRequest("code", "state-a"), response
        ).displayName()).isEqualTo("Alex A");
        assertThat(controller.exchange(
                new DingTalkAuthController.ExchangeRequest("code", "state-b"), response
        ).displayName()).isEqualTo("Alex B");

        verify(directory).findActiveByDingTalkId("shared-external-id", ORGANIZATION_A);
        verify(directory).findActiveByDingTalkId("shared-external-id", ORGANIZATION_B);
    }

    @Test
    void deniesDingTalkIdentityThatExistsOnlyInAnotherOrganization() {
        IdentityDirectory directory = mock(IdentityDirectory.class);
        DingTalkLoginService loginService = mock(DingTalkLoginService.class);
        DingTalkAuthClient authClient = mock(DingTalkAuthClient.class);
        SessionTokenService sessions = mock(SessionTokenService.class);
        BrowserSessionCookieService cookies = mock(BrowserSessionCookieService.class);
        AuditService audit = mock(AuditService.class);
        when(loginService.consumeState("state-b")).thenReturn(ORGANIZATION_B);
        when(authClient.exchange("code")).thenReturn(
                new DingTalkAuthClient.DingTalkIdentity("external-a", "Alex", null)
        );
        when(directory.findActiveByDingTalkId("external-a", ORGANIZATION_B))
                .thenReturn(Optional.empty());
        DingTalkAuthController controller = new DingTalkAuthController(
                authClient, sessions, audit, loginService, cookies, directory
        );

        assertThatThrownBy(() -> controller.exchange(
                new DingTalkAuthController.ExchangeRequest("code", "state-b"),
                mock(HttpServletResponse.class)
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.code()).isEqualTo("DINGTALK_USER_NOT_SYNCED");
            assertThat(exception.status().value()).isEqualTo(403);
        });
        verify(sessions, never()).issue(org.mockito.ArgumentMatchers.any());
    }

    private static void authenticate(SessionSubject subject) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(subject, null)
        );
    }
}
