package com.zoro.legaloa.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zoro.legaloa.common.BusinessException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class DingTalkLoginServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    void shouldExposeOnlyNonSecretLoginConfiguration() {
        DingTalkLoginService service = service("dingtalk");

        var configuration = service.configuration();

        assertThat(configuration.authMode()).isEqualTo("dingtalk");
        assertThat(configuration.dingtalkConfigured()).isTrue();
        assertThat(configuration.authorizationStateTtlSeconds()).isEqualTo(600);
    }

    @Test
    void shouldCreateOneTimeStateAndEncodedAuthorizationUrl() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        DingTalkLoginService service = service("dingtalk");

        var response = service.begin();

        assertThat(response.authorizationUrl())
                .startsWith("https://login.dingtalk.com/oauth2/auth?")
                .contains("response_type=code")
                .contains("client_id=ding-test-client")
                .contains("scope=openid")
                .contains("prompt=consent")
                .contains("redirect_uri=https%3A%2F%2Foa.example.test%2Fauth%2Fdingtalk%2Fcallback");
        verify(values).set(
                startsWith("lawoa:oauth-state:"),
                eq("PENDING"),
                eq(Duration.ofMinutes(10))
        );
    }

    @Test
    void shouldConsumeStateExactlyOnce() {
        DingTalkLoginService service = service("dingtalk");
        when(redis.delete("lawoa:oauth-state:valid-state")).thenReturn(true, false);

        service.consumeState("valid-state");

        assertThatThrownBy(() -> service.consumeState("valid-state"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("登录请求已失效");
    }

    private DingTalkLoginService service(String mode) {
        return new DingTalkLoginService(
                redis,
                mode,
                "ding-test-client",
                "test-client-secret",
                "https://oa.example.test/auth/dingtalk/callback"
        );
    }
}
